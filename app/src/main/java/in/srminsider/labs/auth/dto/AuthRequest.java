package in.srminsider.labs.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRequest {

    @NotBlank(message = "Username is required")
    @Size(max = 120, message = "Username is too long")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(max = 200, message = "Password is too long")
    private String password;
}
