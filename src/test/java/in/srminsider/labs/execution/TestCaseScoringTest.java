package in.srminsider.labs.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class TestCaseScoringTest {

    @Test
    void fiveTestsWorthFiveMarksGiveOneMarkEach() {
        assertThat(CodeExecutionService.scoreFor(5, 5, 5)).isEqualTo(5);
        assertThat(CodeExecutionService.scoreFor(4, 5, 5)).isEqualTo(4);
        assertThat(CodeExecutionService.scoreFor(3, 5, 5)).isEqualTo(3);
        assertThat(CodeExecutionService.scoreFor(1, 5, 5)).isEqualTo(1);
        assertThat(CodeExecutionService.scoreFor(0, 5, 5)).isZero();
    }

    @Test
    void tenTestsWorthFiveMarksGiveOneMarkPerTwoPassed() {
        assertThat(CodeExecutionService.scoreFor(10, 10, 5)).isEqualTo(5);
        assertThat(CodeExecutionService.scoreFor(8, 10, 5)).isEqualTo(4);
        assertThat(CodeExecutionService.scoreFor(4, 10, 5)).isEqualTo(2);
        assertThat(CodeExecutionService.scoreFor(2, 10, 5)).isEqualTo(1);
        // 1 of 10 is half a mark, which now rounds up rather than to nothing.
        assertThat(CodeExecutionService.scoreFor(1, 10, 5)).isEqualTo(1);
        // Passing nothing still earns nothing.
        assertThat(CodeExecutionService.scoreFor(0, 10, 5)).isZero();
    }

    @Test
    void partialWorkRoundsToTheNearestMarkRatherThanDown() {
        // Half of 5 marks is 2.5 and 90% of 5 is 4.5; flooring handed back 2
        // and 4, costing a student most of a mark for work they had done.
        assertThat(CodeExecutionService.scoreFor(5, 10, 5)).isEqualTo(3);
        assertThat(CodeExecutionService.scoreFor(9, 10, 5)).isEqualTo(5);
        assertThat(CodeExecutionService.scoreFor(1, 4, 15)).isEqualTo(4);
        assertThat(CodeExecutionService.scoreFor(3, 8, 15)).isEqualTo(6);
    }

    @Test
    void passingEveryTestAlwaysAwardsFullMarks() {
        assertThat(CodeExecutionService.scoreFor(3, 3, 10)).isEqualTo(10);
        assertThat(CodeExecutionService.scoreFor(7, 7, 5)).isEqualTo(5);
        assertThat(CodeExecutionService.scoreFor(12, 12, 3)).isEqualTo(3);
    }

    @Test
    void degenerateInputsScoreZeroRatherThanThrow() {
        assertThat(CodeExecutionService.scoreFor(0, 0, 5)).isZero();
        assertThat(CodeExecutionService.scoreFor(3, 0, 5)).isZero();
        assertThat(CodeExecutionService.scoreFor(3, 5, 0)).isZero();
        assertThat(CodeExecutionService.scoreFor(-1, 5, 5)).isZero();
    }
}
