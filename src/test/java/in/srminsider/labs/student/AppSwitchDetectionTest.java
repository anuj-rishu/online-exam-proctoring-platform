package in.srminsider.labs.student;

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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alt-tabbing to another application is a different act from switching
 * browser tabs, and a proctor reading the report needs to be able to tell
 * them apart — so they are counted separately rather than lumped together.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppSwitchDetectionTest {

    private static final String PREFIX = "app-switch-test-";
    private static final String STUDENT = PREFIX + "student@example.invalid";
    private static final int YEAR = 1;
    private static final String DOMAIN = "AppSwitchTestDomain";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private SessionCacheService sessionCache;

    @Autowired
    private JwtUtil jwtUtil;

    private String studentBearer;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder().username(STUDENT).email(STUDENT)
                .password("{noop}unused").role(Role.STUDENT).year(YEAR).domain(DOMAIN).build());
        sessionRepository.save(TestSession.builder()
                .username(STUDENT).year(YEAR).domain(DOMAIN).submitted(false)
                .createdAt(System.currentTimeMillis()).build());
        studentBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(STUDENT, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
        sessionCache.evict(STUDENT, YEAR, DOMAIN);
    }

    private String anomalyBody(String type) {
        return """
                {"year":%d,"domain":"%s","type":"%s","description":"test"}
                """.formatted(YEAR, DOMAIN, type);
    }

    @Test
    void switchingToAnotherApplicationIsRecordedAndCounted() throws Exception {
        mockMvc.perform(post("/api/student/session/anomaly")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anomalyBody("APP_SWITCH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appSwitchCount").value(1));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAppSwitchCount()).isEqualTo(1);
        assertThat(session.getAnomalies()).hasSize(1);
        assertThat(session.getAnomalies().get(0).getType()).isEqualTo("APP_SWITCH");
    }

    @Test
    void appSwitchesAndTabSwitchesAreCountedSeparately() throws Exception {
        mockMvc.perform(post("/api/student/session/anomaly")
                .header("Authorization", studentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(anomalyBody("APP_SWITCH")));

        mockMvc.perform(post("/api/student/session/anomaly")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anomalyBody("TAB_SWITCH")))
                .andExpect(jsonPath("$.appSwitchCount").value(1))
                .andExpect(jsonPath("$.tabSwitchCount").value(1));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAppSwitchCount())
                .as("leaving for another app must not inflate the tab-switch count, or vice versa")
                .isEqualTo(1);
        assertThat(session.getTabSwitchCount()).isEqualTo(1);
        assertThat(session.getAnomalies()).hasSize(2);
    }

    @Test
    void repeatedAppSwitchesAccumulate() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/student/session/anomaly")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(anomalyBody("APP_SWITCH")))
                    .andExpect(jsonPath("$.appSwitchCount").value(i));
        }
    }

    @Test
    void theReportBreaksDownEveryViolationTypeSeparately() throws Exception {
        // Two tab switches, three paste attempts, one screenshot.
        for (String type : new String[]{"TAB_SWITCH", "TAB_SWITCH",
                "COPY_PASTE", "COPY_PASTE", "COPY_PASTE", "SCREENSHOT"}) {
            mockMvc.perform(post("/api/student/session/anomaly")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(anomalyBody(type)))
                    .andExpect(status().isOk());
        }

        String superName = PREFIX + "super2@example.invalid";
        userRepository.save(User.builder().username(superName).email(superName)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());
        String superBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(superName, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/admin/submissions/{username}/session", STUDENT)
                            .header("Authorization", superBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.anomalyCounts.TAB_SWITCH").value(2))
                    .andExpect(jsonPath("$.anomalyCounts.COPY_PASTE").value(3))
                    .andExpect(jsonPath("$.anomalyCounts.SCREENSHOT").value(1))
                    .andExpect(jsonPath("$.tabSwitchCount").value(2))
                    .andExpect(jsonPath("$.screenshotCount").value(1))
                    .andExpect(jsonPath("$.anomalies.length()").value(6));
        } finally {
            userRepository.findByUsername(superName).ifPresent(userRepository::delete);
        }
    }

    @Test
    void theViolationReportExposesTheAppSwitchCount() throws Exception {
        mockMvc.perform(post("/api/student/session/anomaly")
                .header("Authorization", studentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(anomalyBody("APP_SWITCH")));

        String superName = PREFIX + "super@example.invalid";
        userRepository.save(User.builder().username(superName).email(superName)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());
        String superBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(superName, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/admin/submissions/{username}/session", STUDENT)
                            .header("Authorization", superBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.appSwitchCount").value(1));
        } finally {
            userRepository.findByUsername(superName).ifPresent(userRepository::delete);
        }
    }
}
