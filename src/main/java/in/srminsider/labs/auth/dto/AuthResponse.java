package in.srminsider.labs.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String token;
    private String role;
    private String username;
    private String email;
    private String name;

    public AuthResponse(String token, String role, String username) {
        this.token = token;
        this.role = role;
        this.username = username;
    }

    public AuthResponse(String token, String role, String username, String email) {
        this.token = token;
        this.role = role;
        this.username = username;
        this.email = email;
    }
}
