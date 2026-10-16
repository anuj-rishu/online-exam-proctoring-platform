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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exam is desktop-only. The page blocks small screens itself, but that
 * check is JavaScript a student can edit — so what actually matters is that
 * the server refuses to mark the session hardware-validated, since nothing
 * serves question content until that flag is set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DesktopOnlyGateTest {

    private static final String PREFIX = "desktop-gate-test-";
    private static final String STUDENT = PREFIX + "student@example.invalid";
    private static final int YEAR = 1;
    private static final String DOMAIN = "DesktopGateTestDomain";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private SessionCacheService sessionCache;

    @Autowired
    private StringRedisTemplate redis;

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
        // The hardware gate also demands a recent webcam frame; stand one in
        // so these tests fail on the screen rule alone, not on that.
        sessionCache.recordSnapshot(STUDENT);
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
        sessionCache.evict(STUDENT, YEAR, DOMAIN);
        redis.delete("snapshot:seen:" + STUDENT);
    }

    private String body(Integer width, Integer height, Boolean touchPrimary) {
        StringBuilder sb = new StringBuilder("{")
                .append("\"year\":").append(YEAR)
                .append(",\"domain\":\"").append(DOMAIN).append('"')
                .append(",\"cameraLive\":true,\"micLive\":true,\"fullscreen\":true,\"monitors\":1");
        if (width != null) sb.append(",\"screenWidth\":").append(width);
        if (height != null) sb.append(",\"screenHeight\":").append(height);
        if (touchPrimary != null) sb.append(",\"touchPrimary\":").append(touchPrimary);
        return sb.append('}').toString();
    }

    private boolean isValidated() {
        return sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow().isHardwareValidated();
    }

    @Test
    void aPhoneSizedScreenIsRefused() throws Exception {
        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(390, 844, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("desktop")));

        assertThat(isValidated())
                .as("a refused check must leave the session unvalidated, so no questions are served")
                .isFalse();
    }

    @Test
    void aTabletIsRefusedEvenWhenItsScreenIsWideEnough() throws Exception {
        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1180, 820, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("tablet")));

        assertThat(isValidated()).isFalse();
    }

    @Test
    void aDesktopPasses() throws Exception {
        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1920, 1080, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(isValidated()).isTrue();
    }

    @Test
    void aScreenExactlyAtTheMinimumPasses() throws Exception {
        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TestSessionController.MIN_DESKTOP_WIDTH, 700, false)))
                .andExpect(status().isOk());

        assertThat(isValidated()).isTrue();
    }

    // Common budget laptops; all were refused while the floor sat at 1024.
    @Test
    void anOrdinaryLaptopPassesAtEveryDisplayScaling() throws Exception {
        int[][] laptops = {
                {1366, 768},   // 1366x768 @100%
                {1093, 614},   // 1366x768 @125%
                {911, 576},    // 1366x768 @150%
                {1280, 720},   // 1920x1080 @150%
        };

        for (int[] size : laptops) {
            sessionCache.getSession(STUDENT, YEAR, DOMAIN).ifPresent(s -> {
                s.setHardwareValidated(false);
                sessionCache.saveImmediate(s);
            });

            mockMvc.perform(post("/api/student/session/system-check")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(size[0], size[1], false)))
                    .andExpect(status().isOk());

            assertThat(isValidated())
                    .as("a %dx%d laptop must be able to sit the exam", size[0], size[1])
                    .isTrue();
        }
    }

    @Test
    void anOlderClientThatSendsNoScreenSizeIsNotLockedOut() throws Exception {
        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, null)))
                .andExpect(status().isOk());

        assertThat(isValidated())
                .as("a student mid-exam on a not-yet-reloaded page must not be thrown out on deploy")
                .isTrue();
    }
}
