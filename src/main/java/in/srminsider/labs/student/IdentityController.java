package in.srminsider.labs.student;

import in.srminsider.labs.domain.model.AnomalyLog;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import in.srminsider.labs.proctoring.SignalingHandler;
import in.srminsider.labs.shared.CloudflareR2Service;
import in.srminsider.labs.student.dto.IdentityRequests;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@Validated
@RequestMapping("/api/identity")
public class IdentityController {

    /** Consecutive in-exam checks facing away before it counts as a violation. */
    private static final int LOOK_AWAY_STREAK_THRESHOLD = 2;

    /** Consecutive failed checks before a mismatch is recorded at all. */
    private static final int FACE_MISMATCH_STREAK_THRESHOLD = 2;

    /** Recorded mismatches before the test is blocked pending proctor review. */
    private static final int FACE_MISMATCH_BLOCK_THRESHOLD = 2;

    @Autowired
    private CloudflareR2Service r2Service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityVerificationClient verificationClient;

    @Autowired
    private SessionCacheService sessionCache;

    @Autowired
    private SignalingHandler signalingHandler;

    @Autowired
    private LivenessService livenessService;

    /**
     * Issues a randomised head-turn challenge. A printed photo or a picture
     * on a phone can satisfy face matching, but cannot turn to a direction
     * chosen at random seconds earlier.
     */
    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/liveness/start")
    public ResponseEntity<?> startLiveness() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        LivenessService.Challenge challenge = livenessService.start(username);
        String firstStep = challenge.steps().get(0);

        return ResponseEntity.ok(Map.of(
                "steps", challenge.steps(),
                "currentStep", 0,
                "expected", firstStep,
                "message", LivenessService.promptFor(firstStep)));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/liveness/verify")
    public ResponseEntity<?> verifyLiveness(@Valid @RequestBody IdentityRequests.ImageCheck request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(livenessService.verifyStep(username, request.image()));
    }

    
    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/position-check")
    public ResponseEntity<?> positionCheck(@Valid @RequestBody IdentityRequests.ImageCheck request) {
        return ResponseEntity.ok(verificationClient.checkPosition(request.image()));
    }

    
    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/id-card-check")
    public ResponseEntity<?> idCardCheck(@Valid @RequestBody IdentityRequests.ImageCheck request) {
        return ResponseEntity.ok(verificationClient.checkIdCard(request.image()));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadIdentity(
            Principal principal,
            @RequestBody IdentityUploadRequest request) {

        String username = null;
        if (principal != null) {
            username = principal.getName();
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                username = auth.getName();
            }
        }

        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: please login again"));
        }

        if (request.getFacePhoto() == null || request.getIdCardPhoto() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing photos"));
        }

        if (!request.isCameraLive() || !request.isMicLive() || !request.isFullscreen() || request.getMonitors() > 1) {
            return ResponseEntity.status(403).body(Map.of("error", "Server Validation Failed: Hardware checks did not pass. Disconnect external monitors and enable fullscreen."));
        }

        if (!livenessService.hasPassedRecently(username)) {
            log.warn("[Identity] {} attempted to enroll without completing the liveness challenge", username);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Please complete the liveness check before submitting your photos."));
        }

        // Called for the selfie embedding; an ID-card mismatch is logged, not refused.
        Map<String, Object> matchResult = verificationClient.verifyMatch(request.getFacePhoto(), request.getIdCardPhoto());
        if (Boolean.TRUE.equals(matchResult.get("serviceUnavailable"))) {
            log.error("[Identity] Verification service unavailable for {}", username);
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Identity verification is temporarily unavailable. Please try again shortly."));
        }
        if (!Boolean.TRUE.equals(matchResult.get("isMatch"))) {
            log.warn("[Identity] Face/ID mismatch for {} (allowed through): {}",
                    username, matchResult.get("message"));
        }

        // No embedding means /verify-live silently skips face proctoring, so refuse here.
        if (!(matchResult.get("selfieEmbedding") instanceof List<?> selfieEmbedding)
                || selfieEmbedding.isEmpty()) {
            log.warn("[Identity] No usable face found in the selfie for {}: {}",
                    username, matchResult.get("message"));
            return ResponseEntity.status(422).body(Map.of(
                    "error", "We could not find your face in the webcam photo. "
                            + "Face the camera in good light and retake it."));
        }

        try {
            String faceObjectKey = r2Service.uploadBase64Image(request.getFacePhoto());
            String idCardObjectKey = r2Service.uploadBase64Image(request.getIdCardPhoto());

            Optional<User> optUser = userRepository.findByUsername(username);
            if (optUser.isPresent()) {
                User user = optUser.get();
                user.setFacePhotoUrl(faceObjectKey);
                user.setIdCardPhotoUrl(idCardObjectKey);

                Object embeddingValue = matchResult.get("selfieEmbedding");
                if (embeddingValue instanceof List<?> rawEmbedding) {
                    List<Double> embedding = rawEmbedding.stream()
                            .map(v -> ((Number) v).doubleValue())
                            .toList();
                    user.setFaceEmbedding(embedding);
                }

                userRepository.save(user);
                livenessService.clearPass(username);

                return ResponseEntity.ok(Map.of("message", "Identity verification successful"));
            } else {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
        } catch (Exception e) {
            log.error("[Identity] Upload failed for {}", username, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload identity images. Please try again."));
        }
    }

    /**
     * Periodic in-exam re-check: is the person in frame still the one who
     * enrolled, and is exactly one person visible? Detection runs
     * server-side (the client cannot fake a match), and any violation is
     * logged to the session directly in this same request — unlike most
     * anomaly types, this one isn't left to the client to self-report,
     * since a tampered client has direct motive to suppress exactly this
     * signal.
     *
     * Fails open: if the verification service is unreachable, this is a
     * transient blip in an already-admitted exam, not a reason to raise a
     * false violation or block the student (contrast with the one-time
     * enrollment gate above, which fails closed).
     */
    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/verify-live")
    public ResponseEntity<?> verifyLiveFace(@Valid @RequestBody IdentityRequests.LiveFaceCheck request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        List<Double> referenceEmbedding = optUser.get().getFaceEmbedding();
        if (referenceEmbedding == null || referenceEmbedding.isEmpty()) {
            return ResponseEntity.ok(Map.of("warning", false));
        }

        Map<String, Object> result = verificationClient.checkLiveFace(request.image(), referenceEmbedding);
        if (Boolean.TRUE.equals(result.get("serviceUnavailable"))) {
            return ResponseEntity.ok(Map.of("warning", false));
        }

        int faceCount = result.get("faceCount") instanceof Number n ? n.intValue() : 0;
        boolean isMatch = Boolean.TRUE.equals(result.get("isMatch"));
        boolean lookingAway = Boolean.TRUE.equals(result.get("lookingAway"));
        String message = String.valueOf(result.getOrDefault("message", "Face verification warning"));

        Optional<TestSession> opt = sessionCache.getSession(username, request.year(), request.domain());
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of("warning", false));
        }
        TestSession session = opt.get();

        // Most severe finding wins — a second person in frame matters more
        // than where the student happens to be looking.
        String anomalyType = null;
        if (faceCount > 1) {
            anomalyType = "MULTIPLE_FACES";
        } else if (faceCount == 1 && !isMatch) {
            // Only a mismatch that survives consecutive checks counts. A hand
            // over the mouth or a bottle raised to drink drops the similarity
            // for one frame; an impersonator stays wrong on the next one too.
            int streak = session.getConsecutiveFaceMismatches() + 1;
            if (streak >= FACE_MISMATCH_STREAK_THRESHOLD) {
                anomalyType = "FACE_MISMATCH";
                session.setConsecutiveFaceMismatches(0);
            } else {
                session.setConsecutiveFaceMismatches(streak);
                sessionCache.saveToCache(session);
                return ResponseEntity.ok(Map.of("warning", false));
            }
        } else if (faceCount == 1 && session.getConsecutiveFaceMismatches() != 0) {
            session.setConsecutiveFaceMismatches(0);
            sessionCache.saveToCache(session);
        }

        // Looking away is only worth recording when it persists. A single
        // glance at a keyboard or a moment of thinking is not a violation,
        // so require consecutive checks (~50s apart) before flagging, and
        // re-arm afterwards so a sustained absence keeps producing evidence.
        if (anomalyType == null) {
            if (lookingAway) {
                int streak = session.getConsecutiveLookAways() + 1;
                if (streak >= LOOK_AWAY_STREAK_THRESHOLD) {
                    anomalyType = "LOOKING_AWAY";
                    message = "Student was facing away from the screen across consecutive checks.";
                    session.setConsecutiveLookAways(0);
                } else {
                    session.setConsecutiveLookAways(streak);
                    sessionCache.saveToCache(session);
                    return ResponseEntity.ok(Map.of("warning", false));
                }
            } else if (session.getConsecutiveLookAways() != 0) {
                session.setConsecutiveLookAways(0);
                sessionCache.saveToCache(session);
                return ResponseEntity.ok(Map.of("warning", false));
            } else {
                return ResponseEntity.ok(Map.of("warning", false));
            }
        }

        session.getAnomalies().add(AnomalyLog.builder()
                .timestamp(System.currentTimeMillis())
                .type(anomalyType)
                .description(message)
                .build());

        // Someone other than the enrolled student being in frame points at
        // impersonation, so it is escalated far faster than the other
        // signals: a second occurrence stops the test and hands the decision
        // to a proctor, who can release it from the Blocked Students page.
        boolean blockedNow = false;
        if ("FACE_MISMATCH".equals(anomalyType)) {
            session.setFaceMismatchCount(session.getFaceMismatchCount() + 1);
            if (session.getFaceMismatchCount() >= FACE_MISMATCH_BLOCK_THRESHOLD && !session.isBlocked()) {
                session.setBlocked(true);
                blockedNow = true;
            }
        }

        sessionCache.saveToCache(session);
        signalingHandler.publishProctorAlert(username, request.year(), request.domain(), anomalyType, message);

        if (blockedNow) {
            String blockMessage = "Test blocked after " + session.getFaceMismatchCount()
                    + " face mismatches — a different person was detected in frame.";
            signalingHandler.publishProctorAlert(username, request.year(), request.domain(),
                    "BLOCKED", blockMessage);
            log.warn("[Identity] {} blocked after {} face mismatches", username, session.getFaceMismatchCount());
        }

        log.warn("[Identity] {} for {} in {} / {}: {}", anomalyType, username, request.year(), request.domain(), message);

        Map<String, Object> body = new HashMap<>();
        body.put("warning", true);
        body.put("type", anomalyType);
        body.put("message", message);
        body.put("isBlocked", session.isBlocked());
        body.put("faceMismatchCount", session.getFaceMismatchCount());
        return ResponseEntity.ok(body);
    }
}

@Data
class IdentityUploadRequest {
    private String facePhoto;
    private String idCardPhoto;
    private boolean cameraLive;
    private boolean micLive;
    private boolean fullscreen;
    private int monitors;
}
