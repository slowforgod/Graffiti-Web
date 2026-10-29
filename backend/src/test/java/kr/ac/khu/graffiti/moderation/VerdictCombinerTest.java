package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;
import kr.ac.khu.graffiti.moderation.VerdictCombiner.Decision;
import org.junit.jupiter.api.Test;

class VerdictCombinerTest {

    private static final CheckResult PROFANITY = CheckResult.block("word-filter", "PROFANITY");

    @Test
    void aBlockOfCategoriesTwoToFourRejectsWithItsCategoryOverEverythingElse() {
        Decision d = VerdictCombiner.combine(List.of(PROFANITY, CheckResult.review("chatkhu", "HATE"),
                CheckResult.block("openai-text", "PERSONAL_INFO"), CheckResult.error("x", "TIMEOUT")),
                UncheckedPolicy.PUBLISH, true);

        assertThat(d).isEqualTo(new Decision(ModerationState.REJECTED, "PERSONAL_INFO", false, false));
    }

    @Test
    void reviewOrErrorIsPublishedUncheckedForAnAdminToLookAtLater() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.review("b", "HATE")),
                UncheckedPolicy.HOLD, true)).isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, false));
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.error("b", "TIMEOUT")),
                UncheckedPolicy.HOLD, false)).isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, false));
    }

    @Test
    void profanityAloneShrinksATextAndLeavesADrawingUnchecked() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), PROFANITY, CheckResult.pass("c")),
                UncheckedPolicy.HOLD, true)).isEqualTo(new Decision(ModerationState.PASSED, null, false, true));
        assertThat(VerdictCombiner.combine(List.of(PROFANITY), UncheckedPolicy.HOLD, false))
                .isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, false));
    }

    @Test
    void profanityWithADoubtIsUncheckedAndStillShrunk() {
        assertThat(VerdictCombiner.combine(List.of(PROFANITY, CheckResult.review("chatkhu", "HATE")),
                UncheckedPolicy.HOLD, true)).isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, true));
    }

    @Test
    void onlyRealPassesArePassed() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.pass("b")),
                UncheckedPolicy.HOLD, true)).isEqualTo(new Decision(ModerationState.PASSED, null, false, false));
    }

    @Test
    void aStubOrNoCheckAtAllIsUncheckedAndFollowsThePolicy() {
        List<CheckResult> partly = List.of(CheckResult.pass("a"), CheckResult.skipped("b"));

        assertThat(VerdictCombiner.combine(partly, UncheckedPolicy.PUBLISH, true))
                .isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, false));
        assertThat(VerdictCombiner.combine(partly, UncheckedPolicy.HOLD, true))
                .isEqualTo(new Decision(ModerationState.HELD, null, true, false));
        assertThat(VerdictCombiner.combine(List.of(), UncheckedPolicy.PUBLISH, true).state())
                .isEqualTo(ModerationState.UNCHECKED);
        assertThat(VerdictCombiner.combine(List.of(), UncheckedPolicy.HOLD, true).state())
                .isEqualTo(ModerationState.HELD);
    }

    @Test
    void profanityNextToStubsFollowsThePolicyAndIsShrunkOnlyWhenPublished() {
        List<CheckResult> stubbed = List.of(PROFANITY, CheckResult.skipped("chatkhu"));

        assertThat(VerdictCombiner.combine(stubbed, UncheckedPolicy.PUBLISH, true))
                .isEqualTo(new Decision(ModerationState.UNCHECKED, null, false, true));
        assertThat(VerdictCombiner.combine(stubbed, UncheckedPolicy.HOLD, true))
                .isEqualTo(new Decision(ModerationState.HELD, null, true, false));
    }

    @Test
    void aRejectingBlockWinsEvenNextToStubs() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.block("word-filter", "HATE"),
                CheckResult.skipped("chatkhu")), UncheckedPolicy.PUBLISH, true).state())
                .isEqualTo(ModerationState.REJECTED);
    }
}
