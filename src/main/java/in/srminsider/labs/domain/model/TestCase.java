package in.srminsider.labs.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase {
    private String input;
    private String expectedOutput;
    private boolean sample;
    private String explanation;
}
