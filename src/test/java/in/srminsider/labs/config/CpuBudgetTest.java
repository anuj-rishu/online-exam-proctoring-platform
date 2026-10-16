package in.srminsider.labs.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution concurrency is derived from the host's cores rather than fixed
 * numbers. These pin the arithmetic — particularly that the total CPU handed
 * to student code stays inside the configured share, since overshooting it
 * is what makes the live proctoring feed stutter mid-exam.
 *
 * Pure arithmetic, so no Spring context, database or Redis: this must stay
 * runnable on a machine where those aren't up.
 */
class CpuBudgetTest {

    private CpuBudget budgetWith(double utilisation, int pinnedPool, double pinnedCpus) {
        CpuBudget budget = new CpuBudget();
        ReflectionTestUtils.setField(budget, "utilisation", utilisation);
        ReflectionTestUtils.setField(budget, "configuredPoolSize", pinnedPool);
        ReflectionTestUtils.setField(budget, "configuredCpusPerContainer", pinnedCpus);
        ReflectionTestUtils.invokeMethod(budget, "calculate");
        return budget;
    }

    @Test
    void theDefaultShareSizesItselfToTheHost() {
        CpuBudget budget = budgetWith(0.8, 0, 0);

        assertThat(budget.getAvailableCores())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(budget.getWorkerPoolSize()).isGreaterThanOrEqualTo(2);
        assertThat(budget.getCpusPerContainer()).isBetween(0.25, 1.0);
    }

    @Test
    void totalCpuHandedToStudentCodeStaysWithinTheBudget() {
        CpuBudget budget = budgetWith(0.8, 0, 0);
        double allocated = budget.getWorkerPoolSize() * budget.getCpusPerContainer();

        assertThat(allocated)
                .as("overshooting the share starves the proctoring relay this process also runs")
                .isLessThanOrEqualTo(budget.getAvailableCores() * 0.8 + 0.01);
    }

    @Test
    void aSmallerShareAllocatesLessThanALargerOne() {
        CpuBudget modest = budgetWith(0.4, 0, 0);
        CpuBudget generous = budgetWith(0.9, 0, 0);

        double modestTotal = modest.getWorkerPoolSize() * modest.getCpusPerContainer();
        double generousTotal = generous.getWorkerPoolSize() * generous.getCpusPerContainer();

        assertThat(modestTotal).isLessThan(generousTotal);
    }

    @Test
    void thereAreAlwaysAtLeastTwoWorkersSoOneSlowRunCannotBlockTheQueue() {
        assertThat(budgetWith(0.01, 0, 0).getWorkerPoolSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aContainerIsNeverGivenMoreThanOneCore() {
        // Student submissions are single-threaded in practice, so a bigger
        // slice buys nothing and only starves the other runs.
        assertThat(budgetWith(1.0, 1, 0).getCpusPerContainer()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void explicitOverridesWinOverTheDerivedValues() {
        CpuBudget pinned = budgetWith(0.8, 3, 0.25);

        assertThat(pinned.getWorkerPoolSize()).isEqualTo(3);
        assertThat(pinned.getCpusPerContainer()).isEqualTo(0.25);
        assertThat(pinned.dockerCpusArg()).isEqualTo("0.25");
    }

    @Test
    void anAbsurdShareIsClampedRatherThanTrusted() {
        assertThat(budgetWith(9.0, 0, 0).getWorkerPoolSize())
                .as("a mistyped utilisation must not oversubscribe the machine without limit")
                .isLessThanOrEqualTo(Runtime.getRuntime().availableProcessors());
    }
}
