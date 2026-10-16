package in.srminsider.labs.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private String id;
    private String username;
    private String email;
    private String name;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Role role;
    private Integer year;
    private String domain;

    private String facePhotoUrl;
    private String idCardPhotoUrl;

    /**
     * The SFace embedding computed from the enrollment selfie at identity
     * verification time. Reused for in-exam re-checks (same person still in
     * frame?) without re-processing the ID card photo on every check.
     */
    private List<Double> faceEmbedding;
}
