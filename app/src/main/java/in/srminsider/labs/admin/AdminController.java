package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.shared.BrevoService;
import in.srminsider.labs.admin.dto.AdminRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BrevoService brevoService;

    // Letters and digits only. Excludes I l 1 O 0, which students misread when
    // retyping the password from the credentials email.
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String CHARACTERS = LETTERS + DIGITS;
    private static final int PASSWORD_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * An email address can only belong to one account, whatever its role.
     * Each management page lists only its own role, so reporting a clash as
     * "student already exists" when the address actually belongs to an admin
     * sends the operator hunting through a list the record can never appear
     * in. Find whoever really holds it so the message can say.
     */
    private Optional<User> findAccountHolding(String email) {
        return userRepository.findByEmail(email)
                .or(() -> userRepository.findByUsername(email));
    }

    /** Short form, for a per-row reason in a bulk upload result. */
    private static String conflictReason(User existing) {
        Role role = existing.getRole();
        if (role == null) {
            return "Already registered, but the account has no role set — it needs cleanup";
        }
        return switch (role) {
            case STUDENT -> "Already registered as a student";
            case ADMIN -> "Already registered as an admin";
            case SUPER_ADMIN -> "Already registered as the super admin";
        };
    }

    /** Full sentence, with where to go next, for a single create. */
    private static String conflictMessage(User existing) {
        Role role = existing.getRole();
        if (role == null) {
            return "This email is already registered to an account with no role assigned, "
                    + "so it does not appear in any list. It has to be removed before the address can be reused.";
        }
        return switch (role) {
            case STUDENT -> "This email is already registered as a student"
                    + (existing.getYear() != null && existing.getDomain() != null
                        ? " (Year " + existing.getYear() + ", " + existing.getDomain() + ")"
                        : "")
                    + ".";
            case ADMIN -> "This email is already registered as an admin — see Manage Admins, not the student list.";
            case SUPER_ADMIN -> "This email belongs to the super admin account and cannot be reused.";
        };
    }

    // Seeds one letter and one digit before filling the rest, so a password can
    // never come out all letters or all digits, then shuffles to hide the seeds.
    private String generateSecurePassword() {
        List<Character> chars = new ArrayList<>(PASSWORD_LENGTH);
        chars.add(LETTERS.charAt(random.nextInt(LETTERS.length())));
        chars.add(DIGITS.charAt(random.nextInt(DIGITS.length())));
        while (chars.size() < PASSWORD_LENGTH) {
            chars.add(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(chars, i, j);
        }
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        chars.forEach(sb::append);
        return sb.toString();
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/upload-admins")
    public ResponseEntity<?> uploadAdmins(@RequestParam("file") MultipartFile file) {
        CsvUploadValidator.validate(file);

        List<String> createdAdmins = new ArrayList<>();
        List<String> failedAdmins = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    if (line.toLowerCase().contains("email")) continue;
                }

                String email = line.trim();
                if (email.isEmpty()) continue;

                if (!isValidEmail(email)) {
                    failedAdmins.add(email + " (Invalid Format)");
                    continue;
                }

                Optional<User> adminRowConflict = findAccountHolding(email);
                if (adminRowConflict.isPresent()) {
                    failedAdmins.add(email + " (" + conflictReason(adminRowConflict.get()) + ")");
                    continue;
                }

                String plainPassword = generateSecurePassword();

                User admin = User.builder()
                        .username(email)
                        .email(email)
                        .password(passwordEncoder.encode(plainPassword))
                        .role(Role.ADMIN)
                        .build();

                userRepository.save(admin);
                brevoService.sendAdminCredentialsEmail(email, plainPassword);
                createdAdmins.add(email);
            }

            return ResponseEntity.ok(Map.of(
                    "message", "CSV processing completed",
                    "created", createdAdmins.size(),
                    "failed", failedAdmins.size(),
                    "failedEmails", failedAdmins
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Error processing CSV: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/admins")
    public ResponseEntity<?> createSingleAdmin(@Valid @RequestBody AdminRequests.CreateAdmin payload) {
        String email = payload.email().trim().toLowerCase();
        Optional<User> adminConflict = findAccountHolding(email);
        if (adminConflict.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", conflictMessage(adminConflict.get())));
        }

        String plainPassword = generateSecurePassword();

        User admin = User.builder()
                .username(email)
                .email(email)
                .password(passwordEncoder.encode(plainPassword))
                .role(Role.ADMIN)
                .build();

        userRepository.save(admin);
        brevoService.sendAdminCredentialsEmail(email, plainPassword);

        return ResponseEntity.ok(admin);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/admins")
    public ResponseEntity<List<User>> getAllAdmins() {
        return ResponseEntity.ok(userRepository.findByRole(Role.ADMIN));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/admins/{username}")
    public ResponseEntity<?> deleteAdmin(@PathVariable String username) {
        return userRepository.findByUsername(username)
            .map(user -> {
                if (user.getRole() == Role.ADMIN) {
                    userRepository.delete(user);
                    return ResponseEntity.ok(Map.of("message", "Admin deleted successfully"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete non-admin user"));
                }
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/admins/template")
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        String csvContent = "email\nadmin1@example.com\nadmin2@example.com\n";
        ByteArrayInputStream in = new ByteArrayInputStream(csvContent.getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=admin_template.csv");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new InputStreamResource(in));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/students")
    public ResponseEntity<List<User>> getAllStudents() {
        return ResponseEntity.ok(userRepository.findByRole(Role.STUDENT));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/students")
    public ResponseEntity<?> createSingleStudent(@Valid @RequestBody AdminRequests.CreateStudent payload) {
        String email = payload.email().trim().toLowerCase();
        String name = payload.name().trim();

        Optional<User> studentConflict = findAccountHolding(email);
        if (studentConflict.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", conflictMessage(studentConflict.get())));
        }

        Integer year = payload.year() != null ? payload.year() : 1;
        String domain = payload.domain() != null && !payload.domain().isBlank()
                ? payload.domain().trim()
                : "Web Dev";

        // Questions are selected by exact domain match, so a typo here creates a
        // student no paper can ever be built for.
        if (!ProblemCatalog.isValidDomain(domain)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Domain must be one of: " + ProblemCatalog.domainOptions()));
        }

        String plainPassword = generateSecurePassword();

        User student = User.builder()
                .username(email)
                .email(email)
                .name(name)
                .password(passwordEncoder.encode(plainPassword))
                .role(Role.STUDENT)
                .year(year)
                .domain(domain)
                .build();

        userRepository.save(student);
        brevoService.sendStudentCredentialsEmail(email, name, plainPassword, year, domain);

        return ResponseEntity.ok(student);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/upload-students")
    public ResponseEntity<?> uploadStudents(@RequestParam("file") MultipartFile file) {
        CsvUploadValidator.validate(file);

        List<String> createdStudents = new ArrayList<>();
        List<String> failedStudents = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isFirstLine = true;
            int nameIdx = -1;
            int emailIdx = -1;
            int yearIdx = -1;
            int domainIdx = -1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (isFirstLine) {
                    isFirstLine = false;
                    if (!line.contains("@")) {
                        String[] h = line.split(",");
                        for (int i = 0; i < h.length; i++) {
                            String col = h[i].trim().toLowerCase();
                            if (col.contains("name")) nameIdx = i;
                            else if (col.contains("email") || col.contains("mail")) emailIdx = i;
                            else if (col.contains("year")) yearIdx = i;
                            else if (col.contains("domain") || col.contains("dept")) domainIdx = i;
                        }
                        continue;
                    }
                }

                String[] parts = line.split(",");
                String email = "";
                String name = "";
                Integer year = 1;
                String domain = "Web Dev";

                if (emailIdx != -1 && emailIdx < parts.length) {
                    email = parts[emailIdx].trim();
                }
                if (nameIdx != -1 && nameIdx < parts.length) {
                    name = parts[nameIdx].trim();
                }
                if (yearIdx != -1 && yearIdx < parts.length && !parts[yearIdx].trim().isEmpty()) {
                    try {
                        year = Integer.parseInt(parts[yearIdx].trim());
                    } catch (NumberFormatException ignored) {}
                }
                if (domainIdx != -1 && domainIdx < parts.length && !parts[domainIdx].trim().isEmpty()) {
                    domain = parts[domainIdx].trim();
                }

                if (email.isEmpty()) {
                    if (parts.length == 1 && parts[0].contains("@")) {
                        email = parts[0].trim();
                    } else if (parts[0].contains("@")) {
                        email = parts[0].trim();
                        if (parts.length > 1) {
                            if (parts[1].trim().matches("\\d+")) {
                                try { year = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) {}
                                if (parts.length > 2) domain = parts[2].trim();
                                if (parts.length > 3) name = parts[3].trim();
                            } else {
                                name = parts[1].trim();
                                if (parts.length > 2 && parts[2].trim().matches("\\d+")) {
                                    try { year = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}
                                    if (parts.length > 3) domain = parts[3].trim();
                                }
                            }
                        }
                    } else if (parts.length > 1 && parts[1].contains("@")) {
                        name = parts[0].trim();
                        email = parts[1].trim();
                        if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                            try {
                                year = Integer.parseInt(parts[2].trim());
                            } catch (NumberFormatException ignored) {}
                        }
                        if (parts.length > 3 && !parts[3].trim().isEmpty()) {
                            domain = parts[3].trim();
                        }
                    }
                }

                if (name.isEmpty() && !email.isEmpty() && isValidEmail(email)) {
                    String prefix = email.split("@")[0];
                    String[] words = prefix.split("[._\\-]+");
                    StringBuilder sb = new StringBuilder();
                    for (String w : words) {
                        if (!w.isEmpty()) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(Character.toUpperCase(w.charAt(0)));
                            if (w.length() > 1) sb.append(w.substring(1));
                        }
                    }
                    name = sb.length() > 0 ? sb.toString() : prefix;
                }

                if (!isValidEmail(email)) {
                    failedStudents.add((name.isEmpty() ? "Unknown" : name) + " (" + (email.isEmpty() ? "Empty Email" : email) + " - Invalid Format)");
                    continue;
                }

                Optional<User> rowConflict = findAccountHolding(email);
                if (rowConflict.isPresent()) {
                    failedStudents.add(name + " (" + email + " - " + conflictReason(rowConflict.get()) + ")");
                    continue;
                }

                // Questions are selected by exact domain match, so a typo here
                // creates a student no paper can ever be built for.
                if (!ProblemCatalog.isValidDomain(domain)) {
                    failedStudents.add(name + " (" + email + " - unknown domain \"" + domain
                            + "\", expected one of: " + ProblemCatalog.domainOptions() + ")");
                    continue;
                }

                String plainPassword = generateSecurePassword();

                User student = User.builder()
                        .username(email)
                        .email(email)
                        .name(name)
                        .password(passwordEncoder.encode(plainPassword))
                        .role(Role.STUDENT)
                        .year(year)
                        .domain(domain)
                        .build();

                userRepository.save(student);
                brevoService.sendStudentCredentialsEmail(email, name, plainPassword, year, domain);
                createdStudents.add(name + " <" + email + ">");
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Students CSV processing completed",
                    "created", createdStudents.size(),
                    "failed", failedStudents.size(),
                    "failedEmails", failedStudents
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Error processing CSV: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/students/{idOrUsername}")
    public ResponseEntity<?> deleteStudent(@PathVariable String idOrUsername) {
        User student = userRepository.findById(idOrUsername)
                .or(() -> userRepository.findByUsername(idOrUsername))
                .or(() -> userRepository.findByEmail(idOrUsername))
                .orElse(null);

        if (student == null) {
            return ResponseEntity.notFound().build();
        }

        // A role-less record is invisible in every list and blocks its email
        // from being reused, with no other way out of the UI — so a super
        // admin may clear it here. Real admin accounts still aren't
        // deletable from the student page; they have their own endpoint.
        if (student.getRole() != null && student.getRole() != Role.STUDENT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This account is " + conflictReason(student).toLowerCase()
                            + " — remove it from Manage Admins instead."));
        }

        userRepository.delete(student);
        return ResponseEntity.ok(Map.of("message", student.getRole() == null
                ? "Orphaned account removed — the email address is free to use again."
                : "Student deleted successfully"));
    }

    @GetMapping("/students/template")
    public ResponseEntity<InputStreamResource> downloadStudentTemplate() {
        String csvContent = "email,year,domain,name\nstudent1@srm.edu.in,1,Web Dev,Rahul Sharma\nstudent2@srm.edu.in,2,AIML,Priya Patel\nstudent3@srm.edu.in,1,App Dev,Amit Kumar\n";
        ByteArrayInputStream in = new ByteArrayInputStream(csvContent.getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=student_template.csv");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new InputStreamResource(in));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request, Authentication authentication) {
        String newPassword = request.get("newPassword");
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be between 8 and 200 characters long"));
        }

        String username = authentication.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentAdmin(Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            user = userRepository.findByEmail(username).orElse(null);
        }
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        String email = user.getEmail() != null ? user.getEmail() : (user.getRole() == Role.SUPER_ADMIN ? "admin@srminsider.in" : user.getUsername());
        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "email", email,
                "role", user.getRole().name()
        ));
    }
}
