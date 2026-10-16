package in.srminsider.labs.admin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of fingerprinting rather than comparing strings is that a
 * copied solution still registers after it has been disguised. These pin
 * that behaviour down in both directions: disguised copies must score high,
 * and genuinely different solutions must not.
 *
 * Pure logic — no Spring context or database needed.
 */
class CodeSimilarityServiceTest {

    private final CodeSimilarityService service = new CodeSimilarityService(null, null);

    private static final String ORIGINAL = """
            def find_largest(numbers):
                largest = numbers[0]
                for index in range(1, len(numbers)):
                    if numbers[index] > largest:
                        largest = numbers[index]
                return largest

            values = [4, 8, 15, 16, 23, 42]
            result = find_largest(values)
            print(result)
            """;

    private double score(String a, String b) {
        return service.similarity(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void anIdenticalSubmissionScoresAlmostPerfectly() {
        assertThat(score(ORIGINAL, ORIGINAL)).isGreaterThan(0.99);
    }

    @Test
    void renamingEveryVariableDoesNotHideTheCopy() {
        String renamed = """
                def biggest_value(data):
                    peak = data[0]
                    for cursor in range(1, len(data)):
                        if data[cursor] > peak:
                            peak = data[cursor]
                    return peak

                items = [4, 8, 15, 16, 23, 42]
                answer = biggest_value(items)
                print(answer)
                """;

        assertThat(score(ORIGINAL, renamed))
                .as("identifiers are normalised away, so renaming must not drop the score")
                .isGreaterThan(0.9);
    }

    @Test
    void reformattingAndAddingCommentsDoesNotHideTheCopy() {
        String reformatted = """
                # My solution to the largest-number problem
                def find_largest(numbers):

                    # start with the first one
                    largest = numbers[0]

                    for index in range(1, len(numbers)):   # walk the rest
                        if numbers[index] > largest:
                            largest = numbers[index]   # found a bigger one

                    return largest


                values = [4, 8, 15, 16, 23, 42]
                result = find_largest(values)
                print(result)
                """;

        assertThat(score(ORIGINAL, reformatted))
                .as("comments and blank lines are stripped before hashing")
                .isGreaterThan(0.9);
    }

    @Test
    void aGenuinelyDifferentSolutionScoresLow() {
        String different = """
                import functools

                def largest(numbers):
                    return functools.reduce(lambda a, b: a if a > b else b, numbers)

                print(largest([4, 8, 15, 16, 23, 42]))
                """;

        assertThat(score(ORIGINAL, different))
                .as("a different approach to the same problem must not be flagged as a copy")
                .isLessThan(0.5);
    }

    @Test
    void unrelatedProgramsScoreNearZero() {
        String unrelated = """
                class BankAccount:
                    def __init__(self, owner, balance):
                        self.owner = owner
                        self.balance = balance

                    def deposit(self, amount):
                        if amount <= 0:
                            raise ValueError("deposit must be positive")
                        self.balance = self.balance + amount
                        return self.balance

                    def withdraw(self, amount):
                        if amount > self.balance:
                            raise ValueError("insufficient funds")
                        self.balance = self.balance - amount
                        return self.balance
                """;

        assertThat(score(ORIGINAL, unrelated)).isLessThan(0.3);
    }

    @Test
    void trivallyShortAnswersAreExcludedRatherThanAllFlaggedAsIdentical() {
        Set<Long> fingerprint = service.fingerprint("print(1)");

        assertThat(fingerprint)
                .as("one-liners are identical between honest students too — they must not be compared")
                .isEmpty();
        assertThat(score("print(1)", "print(1)")).isZero();
    }

    @Test
    void javaSubmissionsAreHandledToNotJustPython() {
        String java = """
                public class Main {
                    public static void main(String[] args) {
                        int[] numbers = {4, 8, 15, 16, 23, 42};
                        int largest = numbers[0];
                        for (int i = 1; i < numbers.length; i++) {
                            if (numbers[i] > largest) {
                                largest = numbers[i];
                            }
                        }
                        System.out.println(largest);
                    }
                }
                """;

        String javaRenamed = """
                public class Main {
                    public static void main(String[] args) {
                        int[] data = {4, 8, 15, 16, 23, 42};
                        int peak = data[0];
                        for (int cursor = 1; cursor < data.length; cursor++) {
                            if (data[cursor] > peak) {
                                peak = data[cursor];
                            }
                        }
                        System.out.println(peak);
                    }
                }
                """;

        assertThat(score(java, javaRenamed)).isGreaterThan(0.9);
        assertThat(score(java, ORIGINAL))
                .as("the same algorithm in a different language should not read as a copy")
                .isLessThan(0.6);
    }
}
