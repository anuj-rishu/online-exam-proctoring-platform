package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class IdentityVerificationEndpointTest {

    private static final String STUDENT = "identity-verification-test@example.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();
    private String bearer;

    @BeforeEach
    void createStudent() throws Exception {
        waitForPythonServiceReady();

        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.save(User.builder()
                .username(STUDENT)
                .email(STUDENT)
                .password("{noop}unused")
                .role(Role.STUDENT)
                .year(1)
                .domain("Web Dev")
                .build());

        UserDetails principal = new org.springframework.security.core.userdetails.User(
                STUDENT, "unused",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT")));
        bearer = "Bearer " + jwtUtil.generateToken(principal);
    }

    @AfterEach
    void removeStudent() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
    }

    private void waitForPythonServiceReady() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8500), 500);
                return;
            } catch (Exception e) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException("Python identity-verification service never became reachable on port 8500");
    }

    private String blankImageDataUrl() throws Exception {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    void positionCheckReachesThePythonServiceAndReturnsARealVerdict() throws Exception {
        String body = mapper.writeValueAsString(Map.of("image", blankImageDataUrl()));

        mockMvc.perform(post("/api/identity/position-check")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faceDetected").value(false))
                .andExpect(jsonPath("$.message").value("No face detected"));
    }

    @Test
    void idCardCheckReachesThePythonServiceAndReturnsARealVerdict() throws Exception {
        String body = mapper.writeValueAsString(Map.of("image", blankImageDataUrl()));

        mockMvc.perform(post("/api/identity/id-card-check")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardDetected").exists())
                .andExpect(jsonPath("$.ready").exists());
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        String body = mapper.writeValueAsString(Map.of("image", blankImageDataUrl()));

        mockMvc.perform(post("/api/identity/position-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
