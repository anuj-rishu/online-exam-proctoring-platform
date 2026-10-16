package in.srminsider.labs.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.ProblemRepository;
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
class SectionSequencingTest {

    private static final String STUDENT = "section-order-test-student@example.invalid";
    private static final String YEAR = "1";
    private static final String DOMAIN = "Web Dev";
    private static final String FIRST = "Programming MCQ";
    private static final String SECOND = "Aptitude MCQ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository testSessionRepository;

    @Autowired
    private ProblemRepository problemRepository;

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

    private void createSession() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .param("year", YEAR)
                        .param("domain", DOMAIN)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionOrder[0]").value(FIRST));
    }

    private String sectionBody(String section) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("year", 1);
        body.put("domain", DOMAIN);
        body.put("section", section);
        return mapper.writeValueAsString(body);
    }

    
    private void answerEverythingIn(String section) {
        TestSession session = testSessionRepository.findByUsername(STUDENT).get(0);
        List<Problem> problems = problemRepository.findAllById(session.getProblemIds());
        for (Problem p : problems) {
            if (section.equals(p.getSection())) {
                session.getAnswers().put(p.getId(), "A");
            }
        }
        testSessionRepository.save(session);
        redis.delete("session:" + STUDENT + ":" + YEAR + ":" + DOMAIN);
    }

    @Test
    void aLaterSectionCannotBeStartedBeforeTheEarlierOneIsSubmitted() throws Exception {
        createSession();

        mockMvc.perform(post("/api/student/session/start-section")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(SECOND)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Finish and submit \"" + FIRST + "\" before working on \"" + SECOND + "\"."));
    }

    @Test
    void aSectionCannotBeSubmittedWhileQuestionsAreStillUnanswered() throws Exception {
        createSession();

        mockMvc.perform(post("/api/student/session/submit-section")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(FIRST)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.unanswered").isNumber());
    }

    @Test
    void submittingTheFirstSectionUnlocksTheNextOne() throws Exception {
        createSession();
        answerEverythingIn(FIRST);

        mockMvc.perform(post("/api/student/session/submit-section")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(FIRST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.nextSection").value(SECOND));

        
        mockMvc.perform(post("/api/student/session/start-section")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(SECOND)))
                .andExpect(status().isOk());

        
        mockMvc.perform(post("/api/student/session/start-section")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(FIRST)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("This section is closed and can no longer be edited."));

        TestSession session = testSessionRepository.findByUsername(STUDENT).get(0);
        assertThat(session.getLockedSections()).contains(FIRST);
    }

    // The paper was chosen purely by query parameter until enrolment became authoritative.
    @Test
    void aStudentCannotSitAnotherTracksPaper() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .header("Authorization", bearer)
                        .param("year", YEAR)
                        .param("domain", "AIML"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Web Dev")));

        assertThat(testSessionRepository.findByUsername(STUDENT))
                .as("no session may be created for a track the student is not enrolled in")
                .noneMatch(s -> "AIML".equals(s.getDomain()));
    }

    @Test
    void aStudentCannotSitAnotherYearsPaper() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .header("Authorization", bearer)
                        .param("year", "3")
                        .param("domain", DOMAIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Year 1")));
    }

    @Test
    void theEnrolledTrackIsStillAllowed() throws Exception {
        mockMvc.perform(get("/api/student/session")
                        .header("Authorization", bearer)
                        .param("year", YEAR)
                        .param("domain", DOMAIN))
                .andExpect(status().isOk());
    }
}
