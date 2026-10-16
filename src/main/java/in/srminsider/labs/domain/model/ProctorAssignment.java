package in.srminsider.labs.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "proctor_assignments")
public class ProctorAssignment {

    @Id
    private String id;

    @Indexed(unique = true)
    private String adminUsername;

    @Builder.Default
    private List<String> domains = new ArrayList<>();

    @Builder.Default
    private int capacity = 0;

    private long updatedAt;
    private String updatedBy;
}
