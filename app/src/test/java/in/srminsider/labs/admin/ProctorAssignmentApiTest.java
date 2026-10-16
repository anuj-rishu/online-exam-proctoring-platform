package in.srminsider.labs.admin;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.ProctorAssignmentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
class ProctorAssignmentApiTest {

    private static final String PREFIX = "proctor-api-test-";
    private static final String ADMIN = PREFIX + "admin@example.invalid";
    private static final String SUPER = PREFIX + "super@example.invalid";
    private static final String STUDENT = PREFIX + "student@example.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private ProctorAssignmentRepository assignmentRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminBearer;
    private String superBearer;

    private String bearerFor(String username, String role) {
        return "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                username, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private String studentBearer;

    @Autowired
    private ProctorAssignmentService assignmentService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder().username(ADMIN).email(ADMIN)
                .password("{noop}unused").role(Role.ADMIN).build());
        userRepository.save(User.builder().username(SUPER).email(SUPER)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());
        userRepository.save(User.builder().username(STUDENT).email(STUDENT)
                .password("{noop}unused").role(Role.STUDENT).year(1).domain("Web Dev").build());

        adminBearer = bearerFor(ADMIN, "ADMIN");
        superBearer = bearerFor(SUPER, "SUPER_ADMIN");
        studentBearer = bearerFor(STUDENT, "STUDENT");

        sessionRepository.save(TestSession.builder()
                .username(STUDENT)
                .year(1).domain("Web Dev").submitted(false)
                .createdAt(System.currentTimeMillis()).build());
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        userRepository.findByUsername(SUPER).ifPresent(userRepository::delete);
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        assignmentRepository.findByAdminUsername(ADMIN).ifPresent(assignmentRepository::delete);
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
        redis.delete("session:" + STUDENT + ":1:Web Dev");
    }

    private String scopeBody(String domainsJson, int capacity) {
        return "{\"domains\":" + domainsJson + ",\"capacity\":" + capacity + "}";
    }

    @Test
    void aSuperAdminAssignsDomainsAndCapacity() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Web Dev\"]", 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.capacity").value(20))
                .andExpect(jsonPath("$.domains[0]").value("Web Dev"));
    }

    @Test
    void anOrdinaryAdminCannotAssignScope() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Web Dev\"]", 40)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOrdinaryAdminCannotListEveryProctorsScope() throws Exception {
        mockMvc.perform(get("/api/admin/proctors").header("Authorization", adminBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownCapacityIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Web Dev\"]", 37)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void anUnknownDomainIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Underwater Basket Weaving\"]", 20)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAdminSeesTheirOwnRosterOnceScopeIsAssigned() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Web Dev\"]", 10)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/proctors/me/roster").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.assignedUsernames[0]").value(PREFIX + "student@example.invalid"));
    }

    @Test
    void anAdminWithNoScopeGetsAnEmptyRosterRatherThanEveryone() throws Exception {
        mockMvc.perform(get("/api/admin/proctors/me/roster").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.assignedUsernames").isEmpty());
    }

    
    @Test
    void aStudentLeavingAndRejoiningKeepsTheSameProctor() throws Exception {
        mockMvc.perform(put("/api/admin/proctors/{admin}", ADMIN)
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody("[\"Web Dev\"]", 10)))
                .andExpect(status().isOk());

        assignmentService.claimRoster(ADMIN);
        assertThat(assignmentService.proctorFor(STUDENT)).isNull(); 
        assignmentService.refreshClaimIndex();
        assertThat(assignmentService.proctorFor(STUDENT)).isEqualTo(ADMIN);

        
        
        
        
        mockMvc.perform(get("/api/student/session")
                        .param("year", "1").param("domain", "Web Dev")
                        .header("Authorization", studentBearer))
                .andExpect(status().isOk());

        TestSession afterRejoin = sessionRepository.findByUsername(STUDENT).get(0);
        assertThat(afterRejoin.getAssignedProctor())
                .as("rejoining must not clear or change the proctor claim")
                .isEqualTo(ADMIN);

        assignmentService.refreshClaimIndex();
        assertThat(assignmentService.proctorFor(STUDENT))
                .as("the same admin must still be the one who sees this student's feed after they rejoin")
                .isEqualTo(ADMIN);
    }
}
