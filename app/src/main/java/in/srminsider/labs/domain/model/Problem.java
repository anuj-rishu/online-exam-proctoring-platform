package in.srminsider.labs.domain.model;

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
@Document(collection = "problems")
public class Problem {
    @Id
    private String id;
    private String title;
    private String description;
    private Integer year;
    private String domain;
    private String section;
    private String type;

    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctOption;

    private List<TestCase> testCases;
    private Integer marks;
    private String inputFormat;
    private String outputFormat;
    private String constraints;
    private String sampleCode;

    private String imageBase64;
}
