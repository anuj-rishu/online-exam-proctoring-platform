package in.srminsider.labs.admin;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.AnomalyLog;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The downloadable violation report. Built server-side so the document is
 * the same whoever asks for it and reflects the stored record rather than
 * whatever a page had loaded.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ViolationReportPdfTest {

    private static final String PREFIX = "pdf-report-test-";
    private static final String STUDENT = PREFIX + "student@example.invalid";
    private static final String ADMIN = PREFIX + "admin@example.invalid";
    private static final int YEAR = 2;
    private static final String DOMAIN = "PdfReportTestDomain";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private ViolationReportPdfService pdfService;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminBearer;

    private AnomalyLog anomaly(String type, String description) {
        return AnomalyLog.builder()
                .timestamp(System.currentTimeMillis())
                .type(type)
                .description(description)
                .build();
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder().username(STUDENT).email(STUDENT)
                .password("{noop}unused").role(Role.STUDENT).year(YEAR).domain(DOMAIN).build());
        userRepository.save(User.builder().username(ADMIN).email(ADMIN)
                .password("{noop}unused").role(Role.ADMIN).build());

        sessionRepository.save(TestSession.builder()
                .username(STUDENT).year(YEAR).domain(DOMAIN)
                .submitted(true).submittedAt(System.currentTimeMillis())
                .createdAt(System.currentTimeMillis() - 3_600_000)
                .tabSwitchCount(2)
                .anomalies(List.of(
                        anomaly("TAB_SWITCH", "Student switched tabs."),
                        anomaly("TAB_SWITCH", "Student switched tabs."),
                        anomaly("COPY_PASTE", "Paste from outside the editor blocked.")))
                .build());

        adminBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(ADMIN, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        userRepository.findByUsername(ADMIN).ifPresent(userRepository::delete);
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
    }

    @Test
    void theEndpointReturnsARealPdfAsAnAttachment() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/submissions/{username}/report.pdf", STUDENT)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
                .as("must be a real PDF, not an error page or empty body")
                .startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void theFilenameIsSafeEvenForAnAwkwardUsername() throws Exception {
        mockMvc.perform(get("/api/admin/submissions/{username}/report.pdf", STUDENT)
                        .header("Authorization", adminBearer))
                .andExpect(header().string("Content-Disposition",
                        containsString("violation-report-" + STUDENT.replaceAll("[^A-Za-z0-9._-]", "_"))));
    }

    @Test
    void aMissingStudentIsReportedRatherThanReturningABrokenFile() throws Exception {
        mockMvc.perform(get("/api/admin/submissions/{username}/report.pdf",
                        PREFIX + "nobody@example.invalid")
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnauthenticatedCallerCannotDownloadAStudentsReport() throws Exception {
        mockMvc.perform(get("/api/admin/submissions/{username}/report.pdf", STUDENT))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRenderedDocumentGrowsWithTheNumberOfRecordedEvents() {
        TestSession clean = TestSession.builder()
                .username(STUDENT).year(YEAR).domain(DOMAIN)
                .createdAt(System.currentTimeMillis()).build();

        TestSession busy = TestSession.builder()
                .username(STUDENT).year(YEAR).domain(DOMAIN)
                .createdAt(System.currentTimeMillis())
                .anomalies(java.util.stream.IntStream.range(0, 40)
                        .mapToObj(i -> anomaly("TAB_SWITCH", "Switched tabs, occurrence " + i))
                        .toList())
                .build();

        byte[] cleanPdf = pdfService.render(clean, ADMIN);
        byte[] busyPdf = pdfService.render(busy, ADMIN);

        assertThat(cleanPdf).isNotEmpty();
        assertThat(busyPdf.length)
                .as("the timeline must actually be written into the document")
                .isGreaterThan(cleanPdf.length);
    }
}
