package in.srminsider.labs.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.AnomalyLog;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class UnblockStudentGuardTest {

    private static final String STUDENT = "unblock-guard-test-student@example.invalid";
    private static final String ADMIN = "unblock-guard-test-admin@example.invalid";
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
    void createUsers() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);

        userRepository.save(User.builder()
                .username(STUDENT).email(STUDENT).password("{noop}unused")
                .role(Role.STUDENT).year(1).domain(DOMAIN).build());
        userRepository.save(User.builder()
                .username(ADMIN).email(ADMIN).password("{noop}unused")
                .role(Role.ADMIN).build());

        studentBearer = "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                STUDENT, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        adminBearer = "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                ADMIN, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        testSessionRepository.findByUsername(STUDENT).forEach(testSessionRepository::delete);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    private void createSession() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR).param("domain", DOMAIN)
                        .header("Authorization", studentBearer))
                .andExpect(status().isOk());
    }

    private void blockWithTabSwitches(int count) {
        TestSession session = testSessionRepository.findByUsername(STUDENT).get(0);
        session.setBlocked(true);
        session.setTabSwitchCount(count);
        testSessionRepository.save(session);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    private String unblockBody() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("studentId", STUDENT);
        body.put("year", 1);
        body.put("domain", DOMAIN);
        return mapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.ResultActions unblock() throws Exception {
        return mockMvc.perform(post("/api/student/session/unblock")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unblockBody()));
    }

    @Test
    void aSecondUnblockIsRefusedAndLeavesTheAuditTrailIntact() throws Exception {
        createSession();
        blockWithTabSwitches(4);

        unblock().andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        
        unblock()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This student is not currently blocked."));
        unblock().andExpect(status().isBadRequest());

        TestSession session = testSessionRepository.findByUsername(STUDENT).get(0);
        assertThat(session.isBlocked()).isFalse();

        List<AnomalyLog> unblocks = session.getAnomalies().stream()
                .filter(a -> "ADMIN_UNBLOCK".equals(a.getType()))
                .toList();
        assertThat(unblocks)
                .as("three approve clicks must leave exactly one unblock in the audit trail")
                .hasSize(1);
    }

    @Test
    void unblockingAStudentWhoWasNeverBlockedIsRefused() throws Exception {
        createSession();

        unblock()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This student is not currently blocked."));
    }

    @Test
    void aStudentCannotUnblockThemselves() throws Exception {
        createSession();
        blockWithTabSwitches(4);

        mockMvc.perform(post("/api/student/session/unblock")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unblockBody()))
                .andExpect(status().isForbidden());

        assertThat(testSessionRepository.findByUsername(STUDENT).get(0).isBlocked()).isTrue();
    }
}
