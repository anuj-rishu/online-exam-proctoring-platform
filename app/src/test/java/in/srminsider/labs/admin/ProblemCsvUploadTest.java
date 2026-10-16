package in.srminsider.labs.admin;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.ProblemRepository;
import in.srminsider.labs.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProblemCsvUploadTest {

    private static final String ADMIN = "csv-upload-test-admin@example.invalid";
    
    private static final String TEST_DOMAIN = "Web Dev";
    
    private static final String UPLOAD_DOMAIN = "AIML";
    private static final String HEADER =
            "Title,Year,Domain,Section,OptionA,OptionB,OptionC,OptionD,CorrectOption\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String bearer;

    @BeforeEach
    void createAdmin() {
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        userRepository.save(User.builder()
                .username(ADMIN)
                .email(ADMIN)
                .password("{noop}unused")
                .role(Role.ADMIN)
                .build());

        UserDetails principal = new org.springframework.security.core.userdetails.User(
                ADMIN, "unused",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        bearer = "Bearer " + jwtUtil.generateToken(principal);
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        List<Problem> planted = problemRepository.findAll().stream()
                .filter(p -> p.getTitle() != null && p.getTitle().startsWith("CSVTEST "))
                .toList();
        problemRepository.deleteAll(planted);
    }

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "questions.csv", "text/csv",
                (HEADER + body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTheWholeFileWhenASectionIsMisspelled() throws Exception {
        MockMultipartFile file = csv(
                "CSVTEST good row,1," + UPLOAD_DOMAIN + ",Programming MCQ,a,b,c,d,A\n" +
                "CSVTEST typo row,1," + UPLOAD_DOMAIN + ",Programing MCQ,a,b,c,d,A\n");

        mockMvc.perform(multipart("/api/admin/problems/upload").file(file).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.rowErrors[0]").value(
                        org.hamcrest.Matchers.containsString("Section \"Programing MCQ\" is not one of")));

        assertThat(problemRepository.findAll().stream()
                .anyMatch(p -> "CSVTEST good row".equals(p.getTitle())))
                .as("a valid row must not be saved when another row in the file is invalid")
                .isFalse();
    }

    @Test
    void rejectsAnOutOfRangeYearAndABadCorrectOption() throws Exception {
        MockMultipartFile file = csv(
                "CSVTEST bad year,9," + UPLOAD_DOMAIN + ",Programming MCQ,a,b,c,d,A\n" +
                "CSVTEST bad answer,1," + UPLOAD_DOMAIN + ",Programming MCQ,a,b,c,d,Z\n");

        mockMvc.perform(multipart("/api/admin/problems/upload").file(file).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCount").value(2));
    }

    @Test
    void acceptsAWellFormedFileAndReportsTheCount() throws Exception {
        MockMultipartFile file = csv(
                "CSVTEST valid one,1," + UPLOAD_DOMAIN + ",Programming MCQ,a,b,c,d,A\n" +
                "CSVTEST valid two,2," + UPLOAD_DOMAIN + ",Aptitude MCQ,a,b,c,d,d\n");

        mockMvc.perform(multipart("/api/admin/problems/upload").file(file).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        Problem saved = problemRepository.findAll().stream()
                .filter(p -> "CSVTEST valid two".equals(p.getTitle()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getCorrectOption()).isEqualTo("D");
        assertThat(saved.getType()).isEqualTo("MCQ");
    }

    @Test
    void newlyUploadedQuestionsAppearOnTheFirstPageOfTheAdminList() throws Exception {
        MockMultipartFile file = csv(
                "CSVTEST freshly uploaded,1," + UPLOAD_DOMAIN + ",Programming MCQ,a,b,c,d,A\n");

        mockMvc.perform(multipart("/api/admin/problems/upload").file(file).header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/problems")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("CSVTEST freshly uploaded"));
    }

    @Test
    void programmingQuestionSectionIsVisibleThroughTheExactFilterTheAdminPageSends() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/problems")
                        .param("page", "0")
                        .param("size", "20")
                        .param("domain", TEST_DOMAIN)
                        .param("year", "1")
                        .param("section", "Programming Question")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.totalElements", org.hamcrest.Matchers.greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.content[0].section").value("Programming Question"));
    }
}
