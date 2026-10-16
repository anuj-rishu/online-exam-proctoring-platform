package in.srminsider.labs.admin;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.student.SessionCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class DeleteSubmissionTest {

    private static final String STUDENT = "delete-submission-student@example.invalid";
    private static final String ADMIN = "delete-submission-admin@example.invalid";
    private static final String SUPER = "delete-submission-super@example.invalid";
    private static final String DOMAIN = "Web Dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository testSessionRepository;

    @Autowired
    private SessionCacheService sessionCache;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtUtil jwtUtil;

    private String studentBearer;
    private String adminBearer;
    private String superBearer;

    private String bearerFor(String username, String role) {
        return "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                username, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @BeforeEach
    void setUp() throws Exception {
        for (String u : new String[]{STUDENT, ADMIN, SUPER}) {
            userRepository.findByUsername(u).ifPresent(userRepository::delete);
        }
        userRepository.save(User.builder().username(STUDENT).email(STUDENT)
                .password("{noop}unused").role(Role.STUDENT).year(1).domain(DOMAIN).build());
        userRepository.save(User.builder().username(ADMIN).email(ADMIN)
                .password("{noop}unused").role(Role.ADMIN).build());
        userRepository.save(User.builder().username(SUPER).email(SUPER)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());

        studentBearer = bearerFor(STUDENT, "STUDENT");
        adminBearer = bearerFor(ADMIN, "ADMIN");
        superBearer = bearerFor(SUPER, "SUPER_ADMIN");

        mockMvc.perform(get("/api/student/session")
                        .param("year", "1").param("domain", DOMAIN)
                        .header("Authorization", studentBearer))
                .andExpect(status().isOk());
    }

    @AfterEach
    void cleanUp() {
        for (String u : new String[]{STUDENT, ADMIN, SUPER}) {
            userRepository.findByUsername(u).ifPresent(userRepository::delete);
            testSessionRepository.findByUsername(u).forEach(testSessionRepository::delete);
            redis.delete("session:" + u + ":1:" + DOMAIN);
        }
    }

    private org.springframework.test.web.servlet.ResultActions deleteAs(String bearer) throws Exception {
        return mockMvc.perform(delete("/api/admin/submissions/{username}", STUDENT)
                .param("year", "1")
                .param("domain", DOMAIN)
                .header("Authorization", bearer));
    }

    @Test
    void aSuperAdminCanDeleteASubmission() throws Exception {
        deleteAs(superBearer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(testSessionRepository.findByUsername(STUDENT)).isEmpty();
    }

    @Test
    void anOrdinaryAdminCannotDeleteASubmission() throws Exception {
        deleteAs(adminBearer).andExpect(status().isForbidden());

        assertThat(testSessionRepository.findByUsername(STUDENT))
                .as("the submission must survive a non-super-admin delete attempt")
                .isNotEmpty();
    }

    @Test
    void aStudentCannotDeleteASubmission() throws Exception {
        deleteAs(studentBearer).andExpect(status().isForbidden());

        assertThat(testSessionRepository.findByUsername(STUDENT)).isNotEmpty();
    }

    @Test
    void aDeletedSubmissionIsNotResurrectedByTheCacheFlush() throws Exception {
        
        
        TestSession session = sessionCache.getSession(STUDENT, 1, DOMAIN).orElseThrow();
        session.setTabSwitchCount(2);
        sessionCache.saveToCache(session);

        deleteAs(superBearer).andExpect(status().isOk());

        
        
        sessionCache.flushDirtySessions();

        assertThat(testSessionRepository.findByUsername(STUDENT))
                .as("cache flush must not resurrect a deleted submission")
                .isEmpty();
        assertThat(sessionCache.getSession(STUDENT, 1, DOMAIN)).isEmpty();
    }

    @Test
    void deletingAMissingSubmissionReportsNotFound() throws Exception {
        deleteAs(superBearer).andExpect(status().isOk());
        deleteAs(superBearer).andExpect(status().isNotFound());
    }
}
