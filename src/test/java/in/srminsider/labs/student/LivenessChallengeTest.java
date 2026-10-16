package in.srminsider.labs.student;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.shared.CloudflareR2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Face matching alone can be satisfied by holding up a printed photo, so
 * enrollment is gated behind a head-turn challenge whose direction is chosen
 * server-side at random. The sequence and progress live in Redis rather than
 * the page, so these tests also pin down that editing the client can't skip
 * it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LivenessChallengeTest {

    private static final String PREFIX = "liveness-test-";
    private static final String STUDENT = PREFIX + "student@example.invalid";
    private static final String IMAGE = "data:image/jpeg;base64,ZmFrZQ==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LivenessService livenessService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private IdentityVerificationClient verificationClient;

    @MockitoBean
    private CloudflareR2Service r2Service;

    private String studentBearer;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(User.builder()
                .username(STUDENT).email(STUDENT).password("{noop}unused")
                .role(Role.STUDENT).year(1).domain("Web Dev").build());
        studentBearer = "Bearer " + jwtUtil.generateToken(
                new org.springframework.security.core.userdetails.User(STUDENT, "unused",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(STUDENT).ifPresent(userRepository::delete);
        redis.delete("liveness:challenge:" + STUDENT);
        redis.delete("liveness:passed:" + STUDENT);
    }

    private void poseReturns(String direction) {
        when(verificationClient.checkPose(anyString()))
                .thenReturn(Map.of("faceDetected", true, "direction", direction,
                        "yaw", 0.0, "pitch", 0.0, "message", "ok"));
    }

    private String uploadBody() {
        return """
                {"facePhoto":"%s","idCardPhoto":"%s","cameraLive":true,"micLive":true,"fullscreen":true,"monitors":1}
                """.formatted(IMAGE, IMAGE);
    }

    private void completeLiveness() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        for (String step : livenessService.current(STUDENT).steps()) {
            poseReturns(step);
            mockMvc.perform(post("/api/identity/liveness/verify")
                    .header("Authorization", studentBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"image\":\"" + IMAGE + "\"}"));
        }
    }

    // Mismatch is logged, not refused; the selfie embedding must still be stored.
    @Test
    void aMismatchedIdCardNoLongerBlocksEnrolment() throws Exception {
        when(verificationClient.verifyMatch(anyString(), anyString()))
                .thenReturn(Map.of("isMatch", false, "confidence", 0.05,
                        "selfieEmbedding", List.of(0.1, 0.2, 0.3),
                        "message", "Face does not match the ID card"));
        when(r2Service.uploadBase64Image(anyString())).thenReturn("object-key");

        completeLiveness();

        mockMvc.perform(post("/api/identity/upload")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isOk());

        assertThat(userRepository.findByUsername(STUDENT).orElseThrow().getFaceEmbedding())
                .as("in-exam face checks are skipped entirely without a stored embedding")
                .isNotNull()
                .isNotEmpty();
    }

    // Without an embedding /verify-live skips face proctoring entirely.
    @Test
    void aSelfieWithNoDetectableFaceIsStillRefused() throws Exception {
        when(verificationClient.verifyMatch(anyString(), anyString()))
                .thenReturn(Map.of("isMatch", false, "confidence", 0.0,
                        "message", "No face detected in the webcam photo"));
        when(r2Service.uploadBase64Image(anyString())).thenReturn("object-key");

        completeLiveness();

        mockMvc.perform(post("/api/identity/upload")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(userRepository.findByUsername(STUDENT).orElseThrow().getFaceEmbedding())
                .as("a refused enrolment must not leave a half-set identity behind")
                .isNullOrEmpty();
    }

    @Test
    void aChallengeTurnsBothWaysThenEndsFacingTheCamera() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(0))
                .andExpect(jsonPath("$.steps[2]").value("CENTER"));

        LivenessService.Challenge challenge = livenessService.current(STUDENT);
        assertThat(challenge).isNotNull();
        assertThat(challenge.steps()).hasSize(3);
        assertThat(challenge.steps())
                .as("both directions must be demanded, so one fixed angle can never satisfy the sequence")
                .containsExactlyInAnyOrder("LEFT", "RIGHT", "CENTER");
        assertThat(challenge.steps().get(2)).isEqualTo("CENTER");
    }

    @Test
    void theOrderOfTheTurnsIsNotAlwaysTheSame() {
        java.util.Set<String> firstSteps = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            firstSteps.add(livenessService.start(STUDENT).steps().get(0));
        }
        assertThat(firstSteps)
                .as("a fixed order could be rehearsed with a photo on a turntable")
                .containsExactlyInAnyOrder("LEFT", "RIGHT");
    }

    @Test
    void turningTheWrongWayDoesNotAdvanceTheChallenge() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        String expected = livenessService.current(STUDENT).steps().get(0);
        String wrongWay = "LEFT".equals(expected) ? "RIGHT" : "LEFT";

        poseReturns(wrongWay);

        mockMvc.perform(post("/api/identity/liveness/verify")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + IMAGE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));

        assertThat(livenessService.current(STUDENT).currentStep()).isZero();
        assertThat(livenessService.hasPassedRecently(STUDENT)).isFalse();
    }

    @Test
    void aPhotoHeldAtOneAngleCanNeverCompleteTheSequence() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        String firstStep = livenessService.current(STUDENT).steps().get(0);

        // A printed photo held at the requested angle clears step one...
        poseReturns(firstStep);
        mockMvc.perform(post("/api/identity/liveness/verify")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + IMAGE + "\"}"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.complete").value(false));

        // ...but it is frozen at that angle, so every later step rejects it,
        // however many times it is submitted.
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/identity/liveness/verify")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"image\":\"" + IMAGE + "\"}"))
                    .andExpect(jsonPath("$.ok").value(false));
        }

        assertThat(livenessService.hasPassedRecently(STUDENT))
                .as("a single static pose must never complete the challenge")
                .isFalse();
    }

    @Test
    void completingEveryStepInOrderPasses() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        List<String> steps = livenessService.current(STUDENT).steps();

        for (int i = 0; i < steps.size(); i++) {
            poseReturns(steps.get(i));
            mockMvc.perform(post("/api/identity/liveness/verify")
                            .header("Authorization", studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"image\":\"" + IMAGE + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }

        assertThat(livenessService.hasPassedRecently(STUDENT)).isTrue();
    }

    @Test
    void moreThanOneFaceInFrameIsRejected() throws Exception {
        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        when(verificationClient.checkPose(anyString()))
                .thenReturn(Map.of("faceDetected", true, "direction", "MULTIPLE", "message", "ok"));

        mockMvc.perform(post("/api/identity/liveness/verify")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + IMAGE + "\"}"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("one face")));
    }

    @Test
    void enrollmentIsRefusedWithoutPassingTheChallengeFirst() throws Exception {
        mockMvc.perform(post("/api/identity/upload")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("liveness")));
    }

    @Test
    void oneCompletedChallengeCannotEnrollTwice() throws Exception {
        when(verificationClient.verifyMatch(anyString(), anyString()))
                .thenReturn(Map.of("isMatch", true, "confidence", 0.9,
                        "selfieEmbedding", List.of(0.1, 0.2, 0.3), "message", "ok"));
        when(r2Service.uploadBase64Image(anyString())).thenReturn("object-key");

        mockMvc.perform(post("/api/identity/liveness/start").header("Authorization", studentBearer));
        for (String step : livenessService.current(STUDENT).steps()) {
            poseReturns(step);
            mockMvc.perform(post("/api/identity/liveness/verify")
                    .header("Authorization", studentBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"image\":\"" + IMAGE + "\"}"));
        }

        mockMvc.perform(post("/api/identity/upload")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/identity/upload")
                        .header("Authorization", studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isForbidden());
    }
}
