package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ServerSideValidationTest {

    private static final String STUDENT = "validation-test-student@example.invalid";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

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
                .domain("Web Dev")
                .build());

        UserDetails principal = new org.springframework.security.core.userdetails.User(
                STUDENT, "unused",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT")));
        bearer = "Bearer " + jwtUtil.generateToken(principal);
    }

    @AfterEach
    void removeStudent() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
    }

    private String json(Map<String, Object> body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private void expectBadRequest(String path, String rawBody) throws Exception {
        mockMvc.perform(post(path)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnomalyTypeNotOnTheAllowlist() throws Exception {
        expectBadRequest("/api/student/session/anomaly", json(Map.of(
                "year", 1, "domain", "Web Dev", "type", "TOTALLY_MADE_UP", "description", "x")));
    }

    @Test
    void rejectsOversizedAnomalyDescription() throws Exception {
        expectBadRequest("/api/student/session/anomaly", json(Map.of(
                "year", 1, "domain", "Web Dev", "type", "TAB_SWITCH", "description", "x".repeat(5000))));
    }

    @Test
    void rejectsOutOfRangeYear() throws Exception {
        expectBadRequest("/api/student/session/submit", json(Map.of("year", 99, "domain", "Web Dev")));
    }

    @Test
    void rejectsWronglyTypedFieldInsteadOfCrashing() throws Exception {
        expectBadRequest("/api/student/session/submit", "{\"year\":\"not-a-number\",\"domain\":\"Web Dev\"}");
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        expectBadRequest("/api/student/session/start-section", json(Map.of("year", 1, "domain", "Web Dev")));
    }

    @Test
    void rejectsBadQuestionStatusValue() throws Exception {
        expectBadRequest("/api/student/session/answer", json(Map.of(
                "year", 1, "domain", "Web Dev", "problemId", "abc", "status", "definitely-not-a-status")));
    }

    @Test
    void rejectsOversizedAnswerPayload() throws Exception {
        expectBadRequest("/api/student/session/answer", json(Map.of(
                "year", 1, "domain", "Web Dev", "problemId", "abc", "answer", "x".repeat(25000))));
    }

    @Test
    void rejectsUnsupportedExecutionLanguage() throws Exception {
        expectBadRequest("/api/execute", json(Map.of("language", "ruby", "code", "puts 1")));
    }

    @Test
    void rejectsOversizedCodeSubmission() throws Exception {
        expectBadRequest("/api/execute", json(Map.of("language", "python", "code", "x".repeat(60000))));
    }

    @Test
    void rejectsOutOfRangeYearOnQueryParam() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .header("Authorization", bearer)
                        .param("year", "99")
                        .param("domain", "Web Dev"))
                .andExpect(status().isBadRequest());
    }
}
