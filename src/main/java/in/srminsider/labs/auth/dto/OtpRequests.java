package in.srminsider.labs.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OtpRequests {

    private OtpRequests() {
    }

    public record RequestOtp(
            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email address")
            @Size(max = 120, message = "Email is too long")
            String email
    ) {
    }

    public record VerifyOtp(
            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email address")
            @Size(max = 120, message = "Email is too long")
            String email,

            @NotBlank(message = "OTP is required")
            @Pattern(regexp = "\\d{4,8}", message = "Enter the numeric code from your email")
            String otp
    ) {
    }
}
