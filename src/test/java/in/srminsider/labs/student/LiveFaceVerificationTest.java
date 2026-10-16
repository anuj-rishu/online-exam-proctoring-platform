package in.srminsider.labs.student;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.shared.CloudflareR2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The in-exam face-integrity re-check: detection happens server-side (via
 * the identity-verification service, mocked here so these tests don't need
 * a real detectable face photo), and — unlike most anomaly types — logging
 * happens in the SAME request rather than relying on the client to
 * separately call /anomaly afterward, since a tampered client has direct
 * motive to suppress exactly this signal.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LiveFaceVerificationTest {

    private static final String PREFIX = "live-face-test-";
    private static final String STUDENT = PREFIX + "student@example.invalid";
    private static final int YEAR = 1;
    private static final String DOMAIN = "LiveFaceTestDomain";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private SessionCacheService sessionCache;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private IdentityVerificationClient verificationClient;

    /** Only present so the Spring context can wire IdentityController; unused here. */
    @MockitoBean
    private CloudflareR2Service r2Service;

    private String studentBearer;

    private String bearerFor(String username, String role) {
        return "Bearer " + jwtUtil.generateToken(new org.springframework.security.core.userdetails.User(
                username, "unused", Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder()
                .username(STUDENT).email(STUDENT).password("{noop}unused")
                .role(Role.STUDENT).year(YEAR).domain(DOMAIN)
                .faceEmbedding(List.of(0.1, 0.2, 0.3))
                .build());
        sessionRepository.save(TestSession.builder()
                .username(STUDENT).year(YEAR).domain(DOMAIN).submitted(false)
                .createdAt(System.currentTimeMillis()).build());
        studentBearer = bearerFor(STUDENT, "STUDENT");
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        sessionRepository.deleteAll(sessionRepository.findAll().stream()
                .filter(s -> s.getUsername() != null && s.getUsername().startsWith(PREFIX))
                .toList());
        // The session write path goes through Redis (SessionCacheService),
        // which outlives a single test method — without this, anomalies
        // logged by one test are still cached and bleed into the next.
        sessionCache.evict(STUDENT, YEAR, DOMAIN);
    }

    private String requestBody() {
        return "{\"image\":\"data:image/jpeg;base64,ZmFrZQ==\",\"year\":" + YEAR + ",\"domain\":\"" + DOMAIN + "\"}";
    }

    @Test
    void multipleFacesInFrameIsLoggedAsAnAnomalyAndReturnedAsAWarning() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 2, "isMatch", false, "confidence", 0.0,
                        "message", "Multiple faces detected in frame"));

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.type").value("MULTIPLE_FACES"));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies()).hasSize(1);
        assertThat(session.getAnomalies().get(0).getType()).isEqualTo("MULTIPLE_FACES");
    }

    @Test
    void aDifferentFaceIsLoggedAsAFaceMismatchAnomaly() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", false, "confidence", 0.1,
                        "message", "Different face detected — does not match the enrolled identity"));

        postChecks(1); // first failure only arms the streak

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.type").value("FACE_MISMATCH"));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies()).hasSize(1);
        assertThat(session.getAnomalies().get(0).getType()).isEqualTo("FACE_MISMATCH");
    }

    @Test
    void aMatchingSingleFaceRaisesNoWarningAndLogsNothing() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                        "message", "Face matches enrolled identity"));

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(false));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies()).isEmpty();
    }

    @Test
    void aServiceOutageFailsOpenRatherThanRaisingAFalseViolation() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("serviceUnavailable", true, "isMatch", false,
                        "message", "Verification service is starting up or unavailable — please try again in a moment."));

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(false));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies())
                .as("a transient outage must not be recorded as a proctoring violation")
                .isEmpty();
    }

    @Test
    void aStudentWithNoEnrolledEmbeddingSkipsTheCheckEntirely() throws Exception {
        User noEmbedding = userRepository.findByUsername(STUDENT).orElseThrow();
        noEmbedding.setFaceEmbedding(null);
        userRepository.save(noEmbedding);

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(false));

        verifyNoInteractions(verificationClient);
    }

    @Test
    void asingleGlanceAwayIsNotTreatedAsAViolation() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                        "lookingAway", true, "message", "Face matches enrolled identity"));

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(false));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies())
                .as("looking away once is normal and must not be recorded")
                .isEmpty();
        assertThat(session.getConsecutiveLookAways()).isEqualTo(1);
    }

    @Test
    void sustainedLookingAwayAcrossChecksIsRecorded() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                        "lookingAway", true, "message", "Face matches enrolled identity"));

        mockMvc.perform(post("/api/identity/verify-live")
                .header("Authorization", studentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.type").value("LOOKING_AWAY"));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getAnomalies()).hasSize(1);
        assertThat(session.getAnomalies().get(0).getType()).isEqualTo("LOOKING_AWAY");
        assertThat(session.getConsecutiveLookAways())
                .as("the streak re-arms after flagging so sustained absence keeps producing evidence")
                .isZero();
    }

    @Test
    void facingForwardAgainClearsTheLookAwayStreak() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                        "lookingAway", true, "message", "ok"));
        mockMvc.perform(post("/api/identity/verify-live")
                .header("Authorization", studentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                        "lookingAway", false, "message", "ok"));
        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(jsonPath("$.warning").value(false));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getConsecutiveLookAways()).isZero();
        assertThat(session.getAnomalies()).isEmpty();
    }

    @Test
    void aDifferentFaceOutranksLookingAway() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", false, "confidence", 0.1,
                        "lookingAway", true,
                        "message", "Different face detected — does not match the enrolled identity"));

        postChecks(1);

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FACE_MISMATCH"));
    }

    private void faceMismatchReturned() {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 1, "isMatch", false, "confidence", 0.1,
                        "message", "Different face detected — does not match the enrolled identity"));
    }

    // A mismatch is only recorded once it survives consecutive checks, so a
    // transient occlusion (a drink, a hand near the face) is ignored.
    private void postChecks(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(post("/api/identity/verify-live")
                    .header("Authorization", studentBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody()));
        }
    }

    @Test
    void aSingleFaceMismatchWarnsButDoesNotBlockTheTest() throws Exception {
        faceMismatchReturned();
        postChecks(1);

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.isBlocked").value(false))
                .andExpect(jsonPath("$.faceMismatchCount").value(1));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.isBlocked())
                .as("one mismatch could be a glitch — it warns, it does not stop the exam")
                .isFalse();
    }

    @Test
    void aSecondFaceMismatchBlocksTheTest() throws Exception {
        faceMismatchReturned();

        postChecks(3); // two consecutive failures per recorded mismatch

        mockMvc.perform(post("/api/identity/verify-live")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBlocked").value(true))
                .andExpect(jsonPath("$.faceMismatchCount").value(2));

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.isBlocked()).isTrue();
        assertThat(session.getAnomalies())
                .as("both mismatches stay on the record for the proctor to review")
                .hasSize(2);
    }

    // Drinking from a bottle or resting a hand near the mouth drops the match
    // for a single frame. Isolated failures used to accumulate to a block.
    @Test
    void aBriefOcclusionBetweenCleanChecksNeverBlocksTheStudent() throws Exception {
        for (int round = 0; round < 4; round++) {
            faceMismatchReturned();
            postChecks(1);

            when(verificationClient.checkLiveFace(anyString(), anyList()))
                    .thenReturn(Map.of("faceCount", 1, "isMatch", true, "confidence", 0.9,
                            "message", "ok"));
            postChecks(1);
        }

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.isBlocked())
                .as("four separate one-frame drops are not impersonation")
                .isFalse();
        assertThat(session.getFaceMismatchCount()).isZero();
        assertThat(session.getAnomalies())
                .as("nothing reached two consecutive failures, so nothing is on the record")
                .isEmpty();
    }

    @Test
    void otherWarningsDoNotCountTowardTheFaceMismatchBlock() throws Exception {
        when(verificationClient.checkLiveFace(anyString(), anyList()))
                .thenReturn(Map.of("faceCount", 3, "isMatch", false, "confidence", 0.0,
                        "message", "Multiple faces detected in frame"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/identity/verify-live")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody()))
                    .andExpect(jsonPath("$.isBlocked").value(false));
        }

        TestSession session = sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow();
        assertThat(session.getFaceMismatchCount()).isZero();
        assertThat(session.isBlocked()).isFalse();
    }

    @Test
    void aProctorCanReleaseATestBlockedByFaceMismatches() throws Exception {
        faceMismatchReturned();
        postChecks(4);
        assertThat(sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow().isBlocked()).isTrue();

        // Saved as SUPER_ADMIN deliberately: the authorities come from the
        // stored user, not the token's claims, and only a super admin (or the
        // student's own assigned proctor) may release a block.
        String adminName = PREFIX + "admin@example.invalid";
        userRepository.save(User.builder().username(adminName).email(adminName)
                .password("{noop}unused").role(Role.SUPER_ADMIN).build());
        String adminBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(adminName, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        try {
            mockMvc.perform(post("/api/student/session/unblock")
                            .header("Authorization", adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"studentId\":\"" + STUDENT + "\",\"year\":" + YEAR
                                    + ",\"domain\":\"" + DOMAIN + "\"}"))
                    .andExpect(status().isOk());

            assertThat(sessionCache.getSession(STUDENT, YEAR, DOMAIN).orElseThrow().isBlocked())
                    .as("the student must be able to get back in once a proctor reviews it")
                    .isFalse();
        } finally {
            userRepository.findByUsername(adminName).ifPresent(userRepository::delete);
        }
    }

    @Test
    void anUnauthenticatedCallerIsRejected() throws Exception {
        mockMvc.perform(post("/api/identity/verify-live")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden());
    }
}
