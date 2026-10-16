package in.srminsider.labs.execution;

import in.srminsider.labs.domain.model.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end: TestCaseScoringTest only covers the arithmetic, not real execution.
@SpringBootTest
class TestCasesAgainstRealStdinTest {

    @Autowired
    private CodeExecutionService executionService;

    // Reads n then n integers and prints the sum, the shape most questions use.
    private static final List<TestCase> SUM_CASES = List.of(
            TestCase.builder().input("5\n1 2 3 4 5").expectedOutput("15").sample(true).build(),
            TestCase.builder().input("3\n10 20 30").expectedOutput("60").sample(false).build(),
            TestCase.builder().input("1\n42").expectedOutput("42").sample(false).build());

    private void assertScoresFullMarks(String language, String code) {
        Map<String, Object> result = executionService.executeTestCases(language, code, SUM_CASES, 10);

        assertThat(result.get("passedTestCases"))
                .as("%s: a correct answer must pass every test case", language)
                .isEqualTo(SUM_CASES.size());
        assertThat(result.get("totalTestCases")).isEqualTo(SUM_CASES.size());
        assertThat(result.get("score"))
                .as("%s: passing everything must award full marks", language)
                .isEqualTo(10);
    }

    @Test
    void aCorrectPythonAnswerPassesEveryTestCase() {
        assertScoresFullMarks("python", String.join("\n",
                "n = int(input())",
                "print(sum(int(x) for x in input().split()))"));
    }

    @Test
    void aCorrectJavascriptAnswerPassesEveryTestCase() {
        assertScoresFullMarks("javascript", String.join("\n",
                "const lines = require('fs').readFileSync(0, 'utf8').split('\\n');",
                "const nums = lines[1].trim().split(/\\s+/).map(Number);",
                "console.log(nums.reduce((a, b) => a + b, 0));"));
    }

    @Test
    void aCorrectJavaAnswerPassesEveryTestCase() {
        assertScoresFullMarks("java", String.join("\n",
                "import java.util.Scanner;",
                "public class Main {",
                "  public static void main(String[] args) {",
                "    Scanner sc = new Scanner(System.in);",
                "    int n = sc.nextInt();",
                "    long sum = 0;",
                "    for (int i = 0; i < n; i++) sum += sc.nextInt();",
                "    System.out.println(sum);",
                "  }",
                "}"));
    }

    // A wrong answer must still fail.
    @Test
    void anIncorrectAnswerStillFailsItsTestCases() {
        Map<String, Object> result = executionService.executeTestCases(
                "python", "print(0)", SUM_CASES, 10);

        assertThat(result.get("passedTestCases"))
                .as("no expected output is 0, so nothing may match")
                .isEqualTo(0);
        assertThat(result.get("score")).isEqualTo(0);
    }
}
