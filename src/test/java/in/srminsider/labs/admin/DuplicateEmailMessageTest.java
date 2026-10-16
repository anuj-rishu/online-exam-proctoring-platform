package in.srminsider.labs.admin;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every management page lists one role, but an email can only belong to one
 * account overall. Saying "student with this email already exists" when the
 * address is actually held by an admin sends the operator searching a list
 * the record will never appear in — which is exactly the dead end this pins
 * shut.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DuplicateEmailMessageTest {

    private static final String PREFIX = "dupe-email-test-";
    private static final String SUPER = PREFIX + "super@example.invalid";
    private static final String TAKEN = PREFIX + "taken@example.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String superBearer;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder().username(SUPER).email(SUPER)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());
        superBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(SUPER, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(SUPER).ifPresent(userRepository::delete);
        userRepository.findByUsername(TAKEN).ifPresent(userRepository::delete);
        userRepository.findByEmail(TAKEN).ifPresent(userRepository::delete);
    }

    private void existingAccountWithRole(Role role) {
        userRepository.save(User.builder().username(TAKEN).email(TAKEN)
                .password("{noop}unused").role(role).year(2).domain("AIML").build());
    }

    private String createStudentBody() {
        return "{\"name\":\"Test Person\",\"email\":\"" + TAKEN + "\",\"year\":1,\"domain\":\"Web Dev\"}";
    }

    @Test
    void anEmailHeldByAnAdminSaysSoInsteadOfClaimingAStudentExists() throws Exception {
        existingAccountWithRole(Role.ADMIN);

        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createStudentBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("admin")))
                .andExpect(jsonPath("$.error").value(containsString("Manage Admins")));
    }

    @Test
    void anEmailHeldByAStudentStillReportsItAsAStudent() throws Exception {
        existingAccountWithRole(Role.STUDENT);

        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createStudentBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("student")))
                .andExpect(jsonPath("$.error").value(containsString("AIML")));
    }

    @Test
    void anEmailHeldByAStudentIsNamedWhenInvitingAnAdmin() throws Exception {
        existingAccountWithRole(Role.STUDENT);

        mockMvc.perform(post("/api/admin/admins")
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + TAKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("student")));
    }

    @Test
    void aRoleLessOrphanSaysItIsInvisibleRatherThanPointingAtAList() throws Exception {
        userRepository.save(User.builder().username(TAKEN).email(TAKEN)
                .password("{noop}unused").role(null).build());

        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createStudentBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("no role assigned")));
    }

    @Test
    void aRoleLessOrphanCanBeClearedSoItsEmailBecomesUsableAgain() throws Exception {
        userRepository.save(User.builder().username(TAKEN).email(TAKEN)
                .password("{noop}unused").role(null).build());

        mockMvc.perform(delete("/api/admin/students/{id}", TAKEN)
                        .header("Authorization", superBearer))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail(TAKEN))
                .as("the orphan blocking the address must actually be gone")
                .isEmpty();

        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createStudentBody()))
                .andExpect(status().isOk());
    }

    @Test
    void aRealAdminAccountIsStillNotDeletableFromTheStudentPage() throws Exception {
        existingAccountWithRole(Role.ADMIN);

        mockMvc.perform(delete("/api/admin/students/{id}", TAKEN)
                        .header("Authorization", superBearer))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail(TAKEN)).isPresent();
    }
}
