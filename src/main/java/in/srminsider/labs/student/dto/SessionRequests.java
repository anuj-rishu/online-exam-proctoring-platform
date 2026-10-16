package in.srminsider.labs.student.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SessionRequests {

    private SessionRequests() {
    }

    public record SystemCheck(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain,

            @NotNull(message = "Camera status is required")
            Boolean cameraLive,

            @NotNull(message = "Microphone status is required")
            Boolean micLive,

            @NotNull(message = "Fullscreen status is required")
            Boolean fullscreen,

            @NotNull(message = "Monitor count is required")
            @Min(value = 1, message = "Monitor count must be at least 1")
            @Max(value = 16, message = "Monitor count is out of range")
            Integer monitors,

            /**
             * Viewport size and primary input type, so the exam can be held to
             * a desktop. Optional for backwards compatibility with a client
             * that hasn't reloaded yet; when absent the size check is skipped
             * rather than failing everyone mid-exam on deploy.
             */
            @Min(value = 0, message = "Screen width is out of range")
            @Max(value = 30000, message = "Screen width is out of range")
            Integer screenWidth,

            @Min(value = 0, message = "Screen height is out of range")
            @Max(value = 30000, message = "Screen height is out of range")
            Integer screenHeight,

            /** True when the primary pointer is touch — a phone or tablet. */
            Boolean touchPrimary
    ) {
    }

    public record SaveAnswer(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain,

            @Size(max = 64, message = "Question reference is invalid")
            String problemId,

            @Size(max = 20000, message = "Answer exceeds the maximum allowed length")
            String answer,

            @Pattern(regexp = "answered|skipped|review|not_visited", message = "Unknown question status")
            String status,

            @Min(value = 0, message = "Question index cannot be negative")
            @Max(value = 500, message = "Question index is out of range")
            Integer currentIndex,

            @Size(max = 60, message = "Section name is too long")
            String activeSection
    ) {
    }

    public record SectionAction(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain,

            @NotBlank(message = "Section is required")
            @Size(max = 60, message = "Section name is too long")
            String section
    ) {
    }

    public record SubmitTest(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain
    ) {
    }

    public record LogAnomaly(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain,

            @NotBlank(message = "Anomaly type is required")
            @Pattern(regexp = "TAB_SWITCH|APP_SWITCH|FACE_NOT_VISIBLE|MULTIPLE_FACES|FACE_MISMATCH|LOOKING_AWAY|VOICE_DETECTED|FULLSCREEN_EXIT|COPY_PASTE|DEVTOOLS|SCREENSHOT|OTHER",
                     message = "Unknown anomaly type")
            String type,

            @Size(max = 500, message = "Description exceeds the maximum allowed length")
            String description
    ) {
    }

    /**
     * A summary of what the microphone heard over a short sampling window.
     * The browser measures, the server decides — the thresholds and the
     * resulting violation record are deliberately kept server-side.
     */
    public record VoiceCheck(
            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain,

            /** Fraction of sampled frames carrying speech-band energy, 0.0–1.0. */
            @NotNull(message = "Speech ratio is required")
            @DecimalMin(value = "0.0", message = "Speech ratio must be between 0 and 1")
            @DecimalMax(value = "1.0", message = "Speech ratio must be between 0 and 1")
            Double speechRatio,

            /** Loudest speech-band level seen in the window, 0.0–1.0. */
            @DecimalMin(value = "0.0", message = "Peak level must be between 0 and 1")
            @DecimalMax(value = "1.0", message = "Peak level must be between 0 and 1")
            Double peakLevel
    ) {
    }

    public record UnblockStudent(
            @NotBlank(message = "Student is required")
            @Size(max = 120, message = "Student identifier is too long")
            String studentId,

            @NotNull(message = "Year is required")
            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @NotBlank(message = "Domain is required")
            @Size(max = 60, message = "Domain is too long")
            String domain
    ) {
    }
}
