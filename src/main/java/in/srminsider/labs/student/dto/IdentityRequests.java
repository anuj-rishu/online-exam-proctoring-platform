package in.srminsider.labs.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class IdentityRequests {

    private IdentityRequests() {
    }

    public record ImageCheck(
            @NotBlank(message = "Image is required")
            @Size(max = 2_000_000, message = "Image is too large")
            String image
    ) {
    }

    public record LiveFaceCheck(
            @NotBlank(message = "Image is required")
            @Size(max = 2_000_000, message = "Image is too large")
            String image,
            @NotNull(message = "Year is required")
            Integer year,
            @NotBlank(message = "Domain is required")
            String domain
    ) {
    }
}
