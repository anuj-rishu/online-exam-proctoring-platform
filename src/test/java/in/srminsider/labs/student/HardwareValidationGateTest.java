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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class HardwareValidationGateTest {

    private static final String STUDENT = "hardware-gate-test-student@example.invalid";
    private static final String YEAR = "1";
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
        redis.delete("snapshot:seen:" + STUDENT);
    }

    @Test
    void questionDetailsAreBlockedUntilSystemCheckPassesThenUnblockAfter() throws Exception {
        MvcResult sessionResult = mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems").isArray())
                .andReturn();

        String firstProblemId = com.jayway.jsonpath.JsonPath.read(
                sessionResult.getResponse().getContentAsString(), "$.problems[0].id");
        assertThat(firstProblemId).isNotBlank();

        
        
        mockMvc.perform(get("/api/student/session/problem/{id}", firstProblemId)
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Hardware validation is pending."));

        
        
        sessionCache.recordSnapshot(STUDENT);

        String checkBody = mapper.writeValueAsString(Map.of(
                "year", 1, "domain", DOMAIN,
                "cameraLive", true, "micLive", true,
                "fullscreen", true, "monitors", 1));

        mockMvc.perform(post("/api/student/session/system-check")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        
        mockMvc.perform(get("/api/student/session/problem/{id}", firstProblemId)
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").isNotEmpty());
    }
}
