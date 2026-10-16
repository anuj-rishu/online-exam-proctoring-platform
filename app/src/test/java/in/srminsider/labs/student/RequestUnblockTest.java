package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
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
class RequestUnblockTest {

    private static final String STUDENT = "request-unblock-test-student@example.invalid";
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
    private String bearer;

    @BeforeEach
    void createStudent() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.save(User.builder()
                .username(STUDENT)
                .email(STUDENT)
                .password("{noop}unused")
                .role(Role.STUDENT)
                .year(1)
                .domain(DOMAIN)
                .build());

        UserDetails principal = new org.springframework.security.core.userdetails.User(
                STUDENT, "unused",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT")));
        bearer = "Bearer " + jwtUtil.generateToken(principal);
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        testSessionRepository.findByUsername(STUDENT).forEach(testSessionRepository::delete);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    private String createSessionAndReturnId() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isOk());
        return testSessionRepository.findByUsername(STUDENT).get(0).getId();
    }

    private void markBlockedDirectly() {
        TestSession session = testSessionRepository.findByUsername(STUDENT).get(0);
        session.setBlocked(true);
        testSessionRepository.save(session);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    private String requestBody() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("year", 1);
        body.put("domain", DOMAIN);
        return mapper.writeValueAsString(body);
    }

    @Test
    void succeedsOverPlainHttpWithNoWebSocketInvolvedWhenSessionIsBlocked() throws Exception {
        createSessionAndReturnId();
        markBlockedDirectly();

        mockMvc.perform(post("/api/student/session/request-unblock")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalies[?(@.type == 'UNBLOCK_REQUESTED')]").exists());
    }

    @Test
    void rejectsTheRequestWhenTheSessionIsNotActuallyBlocked() throws Exception {
        createSessionAndReturnId();

        mockMvc.perform(post("/api/student/session/request-unblock")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Your test is not currently blocked."));
    }
}
