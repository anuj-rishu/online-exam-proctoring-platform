package in.srminsider.labs.auth;

import in.srminsider.labs.auth.dto.AuthRequest;
import in.srminsider.labs.auth.dto.OtpRequests;
import jakarta.validation.Valid;
import in.srminsider.labs.auth.dto.AuthResponse;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.shared.BrevoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    @Value("${app.superadmin.email:admin@srminsider.in}")
    private String superAdminEmail;

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final BrevoService brevoService;
    private final OtpService otpService;

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(@Valid @RequestBody AuthRequest authRequest) {
        String identifier = authRequest.getUsername() != null ? authRequest.getUsername().trim() : "";
        User user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .orElse(null);

        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect username or password"));
        }

        // Trimmed because credentials are copied out of an email, which often
        // carries a trailing space or newline; generated passwords never
        // contain whitespace, so nothing legitimate is lost.
        String password = authRequest.getPassword() != null ? authRequest.getPassword().trim() : "";

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), password)
            );
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect username or password"));
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        final String jwt = jwtUtil.generateToken(userDetails);

        String userEmail = user.getEmail() != null ? user.getEmail() : (user.getRole() == Role.SUPER_ADMIN ? "admin@srminsider.in" : user.getUsername());
        String userName = user.getName() != null ? user.getName() : user.getUsername();
        return ResponseEntity.ok(new AuthResponse(jwt, user.getRole().name(), user.getUsername(), userEmail, userName));
    }

    @PostMapping("/request-otp")
    public ResponseEntity<?> requestOtp(@Valid @RequestBody OtpRequests.RequestOtp request) {
        String trimmedEmail = request.email().trim();
        User user = userRepository.findByEmail(trimmedEmail).orElse(null);
        if (user == null && trimmedEmail.equalsIgnoreCase(superAdminEmail)) {
            user = User.builder()
                    .username("superadmin")
                    .email(superAdminEmail)
                    .role(Role.SUPER_ADMIN)
                    .build();
            userRepository.save(user);
        }

        if (user == null || user.getRole() != Role.SUPER_ADMIN) {
            return ResponseEntity.status(403).body(Map.of("error", "Unauthorized email address"));
        }

        String otp = otpService.generateOtp(trimmedEmail);
        brevoService.sendOtpEmail(trimmedEmail, otp);

        return ResponseEntity.ok(Map.of("message", "OTP sent successfully"));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpRequests.VerifyOtp request) {
        String email = request.email().trim();
        String otp = request.otp();

        if (!otpService.verifyOtp(email, otp)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired OTP"));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        final String jwt = jwtUtil.generateToken(userDetails);

        String userEmail = user.getEmail() != null ? user.getEmail() : email;
        String userName = user.getName() != null ? user.getName() : user.getUsername();
        return ResponseEntity.ok(new AuthResponse(jwt, user.getRole().name(), user.getUsername(), userEmail, userName));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(org.springframework.security.core.Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElse(null);

        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        String email = user.getEmail() != null ? user.getEmail() : (user.getRole() == Role.SUPER_ADMIN ? superAdminEmail : user.getUsername());
        String name = user.getName() != null ? user.getName() : user.getUsername();

        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("username", user.getUsername());
        resp.put("name", name);
        resp.put("email", email);
        resp.put("role", user.getRole().name());
        if (user.getYear() != null) resp.put("year", user.getYear());
        if (user.getDomain() != null) resp.put("domain", user.getDomain());

        return ResponseEntity.ok(resp);
    }
}
