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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class BlockedStudentsListTest {

    private static final String BLOCKED = "blocked-list-blocked@example.invalid";
    private static final String FREE = "blocked-list-free@example.invalid";
    private static final String ADMIN = "blocked-list-admin@example.invalid";
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

    private String adminBearer;

    private String bearerFor(String username, String role) {
        return "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                username, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @BeforeEach
    void setUp() throws Exception {
        for (String u : new String[]{BLOCKED, FREE, ADMIN}) {
            userRepository.findByUsername(u).ifPresent(userRepository::delete);
        }
        userRepository.save(User.builder().username(ADMIN).email(ADMIN)
                .password("{noop}unused").role(Role.ADMIN).build());
        adminBearer = bearerFor(ADMIN, "ADMIN");

        for (String student : new String[]{BLOCKED, FREE}) {
            userRepository.save(User.builder().username(student).email(student)
                    .password("{noop}unused").role(Role.STUDENT).year(1).domain(DOMAIN).build());
            mockMvc.perform(get("/api/student/session")
                            .param("year", "1").param("domain", DOMAIN)
                            .header("Authorization", bearerFor(student, "STUDENT")))
                    .andExpect(status().isOk());
        }
    }

    @AfterEach
    void cleanUp() {
        for (String u : new String[]{BLOCKED, FREE, ADMIN}) {
            userRepository.findByUsername(u).ifPresent(userRepository::delete);
            testSessionRepository.findByUsername(u).forEach(testSessionRepository::delete);
            redis.delete("session:" + u + ":1:" + DOMAIN);
        }
    }

    
    private void blockViaCacheOnly(String username) {
        TestSession session = sessionCache.getSession(username, 1, DOMAIN).orElseThrow();
        session.setBlocked(true);
        session.setTabSwitchCount(4);
        sessionCache.saveToCache(session);
    }

    @Test
    void listsOnlyBlockedStudentsWithTheirViolationCounts() throws Exception {
        blockViaCacheOnly(BLOCKED);

        mockMvc.perform(get("/api/admin/submissions/blocked").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked[?(@.username == '" + BLOCKED + "')]").exists())
                .andExpect(jsonPath("$.blocked[?(@.username == '" + BLOCKED + "')].tabSwitchCount")
                        .value(org.hamcrest.Matchers.hasItem(4)))
                .andExpect(jsonPath("$.blocked[?(@.username == '" + FREE + "')]").doesNotExist());
    }

    @Test
    void aStudentBlockedOnlyInTheCacheStillAppears() throws Exception {
        blockViaCacheOnly(BLOCKED);

        
        
        TestSession stored = testSessionRepository.findByUsername(BLOCKED).get(0);
        org.assertj.core.api.Assertions.assertThat(stored.isBlocked()).isFalse();

        mockMvc.perform(get("/api/admin/submissions/blocked").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked[?(@.username == '" + BLOCKED + "')]").exists());
    }

    @Test
    void anUnblockedStudentDropsOffTheList() throws Exception {
        blockViaCacheOnly(BLOCKED);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/student/session/unblock")
                        .header("Authorization", adminBearer)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + BLOCKED + "\",\"year\":1,\"domain\":\"" + DOMAIN + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/submissions/blocked").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked[?(@.username == '" + BLOCKED + "')]").doesNotExist());
    }

    @Test
    void studentsCannotSeeTheBlockedList() throws Exception {
        mockMvc.perform(get("/api/admin/submissions/blocked")
                        .header("Authorization", bearerFor(FREE, "STUDENT")))
                .andExpect(status().isForbidden());
    }
}
