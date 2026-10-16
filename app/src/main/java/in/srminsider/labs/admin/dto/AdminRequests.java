package in.srminsider.labs.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AdminRequests {

    private AdminRequests() {
    }

    public record CreateAdmin(
            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email address")
            @Size(max = 120, message = "Email is too long")
            String email
    ) {
    }

    public record CreateStudent(
            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email address")
            @Size(max = 120, message = "Email is too long")
            String email,

            @NotBlank(message = "Student full name is required")
            @Size(max = 100, message = "Name is too long")
            String name,

            @Min(value = 1, message = "Year must be between 1 and 4")
            @Max(value = 4, message = "Year must be between 1 and 4")
            Integer year,

            @Size(max = 60, message = "Domain is too long")
            String domain
    ) {
    }

    public record ResetPassword(
            @NotBlank(message = "Username is required")
            @Size(max = 120, message = "Username is too long")
            String username
    ) {
    }
}
