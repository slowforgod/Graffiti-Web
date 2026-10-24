package kr.ac.khu.graffiti.moderation;

import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;

/**
 * Turns the check results into one state (D-42, PROPOSED): any BLOCK rejects, otherwise any REVIEW or ERROR
 * holds, otherwise all PASS publishes as PASSED. When some check was only a stub, the doodle was not really
 * checked: it is UNCHECKED, published or held as {@code graffiti.moderation.unchecked} says. Pure.
 */
public final class VerdictCombiner {

    /** {@code category} is the first blocking (or, when held, reviewing) check's category; may be null. */
    public record Decision(ModerationState state, String category, boolean heldAsUnchecked) {
    }

    private VerdictCombiner() {
    }

    public static Decision combine(List<CheckResult> results, UncheckedPolicy unchecked) {
        CheckResult block = first(results, CheckOutcome.BLOCK);
        if (block != null) {
            return new Decision(ModerationState.REJECTED, block.category(), false);
        }
        CheckResult review = first(results, CheckOutcome.REVIEW);
        if (review != null || first(results, CheckOutcome.ERROR) != null) {
            return new Decision(ModerationState.HELD, review == null ? null : review.category(), false);
        }
        boolean allPassed = !results.isEmpty()
                && results.stream().allMatch(r -> r.outcome() == CheckOutcome.PASS);
        if (allPassed) {
            return new Decision(ModerationState.PASSED, null, false);
        }
        return unchecked == UncheckedPolicy.HOLD
                ? new Decision(ModerationState.HELD, null, true)
                : new Decision(ModerationState.UNCHECKED, null, false);
    }

    private static CheckResult first(List<CheckResult> results, CheckOutcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).findFirst().orElse(null);
    }
}
