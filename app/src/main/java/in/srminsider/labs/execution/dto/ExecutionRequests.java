package in.srminsider.labs.execution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ExecutionRequests {

    private ExecutionRequests() {
    }

    public record RunCode(
            @NotBlank(message = "Language is required")
            @Pattern(regexp = "(?i)python|java|javascript|js", message = "Unsupported language")
            String language,

            @NotBlank(message = "Code is required")
            @Size(max = 50000, message = "Code exceeds the maximum allowed length")
            String code,

            @Size(max = 10000, message = "Input exceeds the maximum allowed length")
            String stdin
    ) {
    }

    public record RunTestCases(
            @NotBlank(message = "Question reference is required")
            @Size(max = 64, message = "Question reference is invalid")
            String problemId,

            @NotBlank(message = "Language is required")
            @Pattern(regexp = "(?i)python|java|javascript|js", message = "Unsupported language")
            String language,

            @NotBlank(message = "Code is required")
            @Size(max = 50000, message = "Code exceeds the maximum allowed length")
            String code
    ) {
    }
}
