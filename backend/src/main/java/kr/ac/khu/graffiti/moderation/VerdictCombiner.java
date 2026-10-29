package kr.ac.khu.graffiti.moderation;

import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;

/**
 * Turns the check results into one state (specs/07-moderation.md §5.3). A BLOCK of categories 2-4 rejects.
 * Otherwise a REVIEW or ERROR publishes as UNCHECKED for an admin to look at afterwards (D-42), as does
 * profanity in a drawing; profanity in a text is shrunk (D-43). When some check was only a stub, the doodle was
 * not really checked: it is UNCHECKED, published or held as {@code graffiti.moderation.unchecked} says. Only
 * when every check passed is it PASSED. Pure.
 */
public final class VerdictCombiner {

    /**
     * {@code category} is the rejecting check's category, or null. {@code shrink}: the text is to be shown at
     * the shrink size.
     */
    public record Decision(ModerationState state, String category, boolean heldAsUnchecked, boolean shrink) {
    }

    private VerdictCombiner() {
    }

    public static Decision combine(List<CheckResult> results, UncheckedPolicy unchecked, boolean text) {
        CheckResult rejecting = results.stream().filter(CheckResult::rejects).findFirst().orElse(null);
        if (rejecting != null) {
            return new Decision(ModerationState.REJECTED, rejecting.category(), false, false);
        }
        boolean profanity = results.stream().anyMatch(CheckResult::profanity);
        boolean shrink = profanity && text;
        if (results.stream().anyMatch(r -> r.outcome() == CheckOutcome.REVIEW || r.outcome() == CheckOutcome.ERROR)
                || profanity && !text) {
            return new Decision(ModerationState.UNCHECKED, null, false, shrink);
        }
        boolean allChecked = !results.isEmpty() && results.stream()
                .allMatch(r -> r.outcome() == CheckOutcome.PASS || r.profanity());
        if (allChecked) {
            return new Decision(ModerationState.PASSED, null, false, shrink);
        }
        return unchecked == UncheckedPolicy.HOLD
                ? new Decision(ModerationState.HELD, null, true, false)
                : new Decision(ModerationState.UNCHECKED, null, false, shrink);
    }
}
