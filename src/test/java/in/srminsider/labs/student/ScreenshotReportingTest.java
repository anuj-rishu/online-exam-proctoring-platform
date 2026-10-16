package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import in.srminsider.labs.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class ScreenshotReportingTest {

    private static final String STUDENT = "screenshot-test-student@example.invalid";
    private static final String ADMIN = "screenshot-test-admin@example.invalid";
    private static final String YEAR = "1";
    private static final String DOMAIN = "Web Dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository testSessionRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtUtil jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();
    private String studentBearer;
    private String adminBearer;

    @BeforeEach
    void createUsers() throws Exception {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);

        userRepository.save(User.builder().username(STUDENT).email(STUDENT)
                .password("{noop}unused").role(Role.STUDENT).year(1).domain(DOMAIN).build());
        userRepository.save(User.builder().username(ADMIN).email(ADMIN)
                .password("{noop}unused").role(Role.ADMIN).build());

        studentBearer = "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                STUDENT, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        adminBearer = "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                ADMIN, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR).param("domain", DOMAIN)
                        .header("Authorization", studentBearer))
                .andExpect(status().isOk());
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        testSessionRepository.findByUsername(STUDENT).forEach(testSessionRepository::delete);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    private void reportScreenshot() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("year", 1);
        body.put("domain", DOMAIN);
        body.put("type", "SCREENSHOT");
        body.put("description", "Screenshot attempt detected (Cmd+Shift+4).");

        mockMvc.perform(post("/api/student/session/anomaly")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void eachDetectedScreenshotIncrementsTheCountReturnedToTheStudent() throws Exception {
        reportScreenshot();
        reportScreenshot();

        Map<String, Object> body = new HashMap<>();
        body.put("year", 1);
        body.put("domain", DOMAIN);
        body.put("type", "SCREENSHOT");
        body.put("description", "Screenshot attempt detected (PrintScreen).");

        mockMvc.perform(post("/api/student/session/anomaly")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenshotCount").value(3))
                
                .andExpect(jsonPath("$.isBlocked").value(false));
    }

    @Test
    void theProctorReportShowsTheScreenshotCountAndTheEvents() throws Exception {
        reportScreenshot();
        reportScreenshot();

        mockMvc.perform(get("/api/admin/submissions/{username}/session", STUDENT)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenshotCount").value(2))
                .andExpect(jsonPath("$.anomalyCounts.SCREENSHOT").value(2))
                .andExpect(jsonPath("$.anomalies[0].type").value("SCREENSHOT"));
    }

    @Test
    void anUnknownAnomalyTypeIsStillRejected() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("year", 1);
        body.put("domain", DOMAIN);
        body.put("type", "NOT_A_REAL_TYPE");
        body.put("description", "should be refused");

        mockMvc.perform(post("/api/student/session/anomaly")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
