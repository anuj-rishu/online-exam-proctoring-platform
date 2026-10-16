package in.srminsider.labs.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
class RunWithStdinTest {

    @Autowired
    private CodeExecutionService executionService;

    private static final String PY_SUM = String.join("\n",
            "n = int(input())",
            "print(sum(int(x) for x in input().split()))");

    @Test
    void suppliedInputReachesTheProgram() {
        Map<String, Object> result = executionService.execute("python", PY_SUM, "5\n1 2 3 4 5\n");

        assertThat(result.get("exitCode")).isEqualTo(0);
        assertThat(((String) result.get("stdout")).trim()).isEqualTo("15");
    }

    @Test
    void withoutInputTheProgramStarvesRatherThanHanging() {
        Map<String, Object> result = executionService.execute("python", PY_SUM, null);

        
        
        assertThat(result.get("exitCode")).isNotEqualTo(0);
        assertThat((String) result.get("stderr")).contains("EOF");
    }

    @Test
    void aProgramThatIgnoresItsInputStillSucceeds() {
        
        
        Map<String, Object> result = executionService.execute(
                "python", "print('done')", "lots\nof\nunread\ninput\n".repeat(200));

        assertThat(result.get("exitCode")).isEqualTo(0);
        assertThat(((String) result.get("stdout")).trim()).isEqualTo("done");
    }
}
