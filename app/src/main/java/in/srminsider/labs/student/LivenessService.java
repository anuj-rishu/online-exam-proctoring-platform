package in.srminsider.labs.student;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Anti-spoofing gate for enrollment.
 *
 * Face matching alone can be satisfied by holding up a printed photo or a
 * picture on a phone. This asks the student to turn their head a way that is
 * chosen at random only moments earlier, which a still image cannot do.
 *
 * The sequence and the student's progress through it are held server-side in
 * Redis, and {@code /api/identity/upload} refuses to enroll anyone who has
 * not completed a challenge recently — so skipping the challenge by editing
 * the page achieves nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LivenessService {

    /** How long a student has to complete a challenge once it is issued. */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    /** How long a completed challenge stays valid for enrolling. */
    private static final Duration PASS_TTL = Duration.ofMinutes(10);

    private static final String CHALLENGE_PREFIX = "liveness:challenge:";
    private static final String PASSED_PREFIX = "liveness:passed:";

    /**
     * Turn both ways, then face forward. Requiring both directions means a
     * printed photo or a picture on a phone has to be physically rotated to
     * three distinct angles in an order it cannot know in advance, and the
     * closing CENTER is what leaves the student framed for the actual
     * capture. The order of the two turns is randomised so the sequence
     * can't be rehearsed.
     */
    private static final List<List<String>> SEQUENCES = List.of(
            List.of("LEFT", "RIGHT", "CENTER"),
            List.of("RIGHT", "LEFT", "CENTER"));

    private final StringRedisTemplate redis;
    private final IdentityVerificationClient verificationClient;
    private final SecureRandom random = new SecureRandom();

    public record Challenge(List<String> steps, int currentStep) {
    }

    private String challengeKey(String username) {
        return CHALLENGE_PREFIX + username;
    }

    private String passedKey(String username) {
        return PASSED_PREFIX + username;
    }

    /** Issues a fresh randomised challenge, discarding any in progress. */
    public Challenge start(String username) {
        List<String> steps = SEQUENCES.get(random.nextInt(SEQUENCES.size()));
        redis.opsForValue().set(challengeKey(username), String.join(",", steps) + "|0", CHALLENGE_TTL);
        redis.delete(passedKey(username));
        return new Challenge(steps, 0);
    }

    public Challenge current(String username) {
        String raw = redis.opsForValue().get(challengeKey(username));
        if (raw == null || !raw.contains("|")) return null;

        String[] parts = raw.split("\\|", 2);
        List<String> steps = List.of(parts[0].split(","));
        int currentStep;
        try {
            currentStep = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new Challenge(steps, currentStep);
    }

    /**
     * Checks one frame against the step the student is currently on.
     * Returns what happened, for the page to render as guidance.
     */
    public Map<String, Object> verifyStep(String username, String imageDataUrl) {
        Challenge challenge = current(username);
        if (challenge == null) {
            return Map.of("ok", false, "expired", true,
                    "message", "Liveness challenge expired — start it again.");
        }

        String expected = challenge.steps().get(challenge.currentStep());
        Map<String, Object> pose = verificationClient.checkPose(imageDataUrl);

        if (Boolean.TRUE.equals(pose.get("serviceUnavailable"))) {
            return Map.of("ok", false, "serviceUnavailable", true,
                    "expected", expected, "currentStep", challenge.currentStep(),
                    "message", "Verification service is unavailable — please try again in a moment.");
        }

        String direction = String.valueOf(pose.getOrDefault("direction", "NONE"));

        if ("MULTIPLE".equals(direction)) {
            return Map.of("ok", false, "expected", expected, "currentStep", challenge.currentStep(),
                    "message", "More than one face is visible — only you should be in frame.");
        }
        if (!Boolean.TRUE.equals(pose.get("faceDetected"))) {
            return Map.of("ok", false, "expected", expected, "currentStep", challenge.currentStep(),
                    "message", "No face detected — make sure you are visible to the camera.");
        }

        if (!expected.equals(direction)) {
            return Map.of("ok", false, "expected", expected, "currentStep", challenge.currentStep(),
                    "detected", direction,
                    "message", promptFor(expected));
        }

        int nextStep = challenge.currentStep() + 1;
        if (nextStep >= challenge.steps().size()) {
            redis.delete(challengeKey(username));
            redis.opsForValue().set(passedKey(username), String.valueOf(System.currentTimeMillis()), PASS_TTL);
            log.info("[Liveness] {} completed the challenge", username);
            return Map.of("ok", true, "complete", true,
                    "message", "Liveness check passed — you can capture your photo now.");
        }

        redis.opsForValue().set(challengeKey(username),
                String.join(",", challenge.steps()) + "|" + nextStep, CHALLENGE_TTL);

        String next = challenge.steps().get(nextStep);
        return Map.of("ok", true, "complete", false, "currentStep", nextStep,
                "expected", next, "message", promptFor(next));
    }

    public boolean hasPassedRecently(String username) {
        return redis.hasKey(passedKey(username));
    }

    /** Consumed on successful enrollment so one pass can't enroll twice. */
    public void clearPass(String username) {
        redis.delete(passedKey(username));
    }

    public static String promptFor(String step) {
        return switch (step) {
            case "LEFT" -> "Slowly turn your head to your left";
            case "RIGHT" -> "Slowly turn your head to your right";
            case "CENTER" -> "Now face the camera straight on";
            default -> "Follow the on-screen instruction";
        };
    }
}
