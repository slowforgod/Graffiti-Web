package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;
import kr.ac.khu.graffiti.moderation.VerdictCombiner.Decision;
import org.junit.jupiter.api.Test;

class VerdictCombinerTest {

    @Test
    void anyBlockRejectsWithItsCategory() {
        Decision d = VerdictCombiner.combine(List.of(CheckResult.review("chatkhu", "HARASSMENT"),
                CheckResult.block("openai-text", "HATE"), CheckResult.error("x", "TIMEOUT")), UncheckedPolicy.PUBLISH);

        assertThat(d).isEqualTo(new Decision(ModerationState.REJECTED, "HATE", false));
    }

    @Test
    void reviewOrErrorHolds() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.review("b", "HARASSMENT")),
                UncheckedPolicy.PUBLISH)).isEqualTo(new Decision(ModerationState.HELD, "HARASSMENT", false));
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.error("b", "TIMEOUT")),
                UncheckedPolicy.PUBLISH)).isEqualTo(new Decision(ModerationState.HELD, null, false));
    }

    @Test
    void onlyRealPassesArePassed() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.pass("a"), CheckResult.pass("b")),
                UncheckedPolicy.HOLD).state()).isEqualTo(ModerationState.PASSED);
    }

    @Test
    void aStubOrNoCheckAtAllIsUncheckedAndFollowsThePolicy() {
        List<CheckResult> partly = List.of(CheckResult.pass("a"), CheckResult.skipped("b"));

        assertThat(VerdictCombiner.combine(partly, UncheckedPolicy.PUBLISH))
                .isEqualTo(new Decision(ModerationState.UNCHECKED, null, false));
        assertThat(VerdictCombiner.combine(partly, UncheckedPolicy.HOLD))
                .isEqualTo(new Decision(ModerationState.HELD, null, true));
        assertThat(VerdictCombiner.combine(List.of(), UncheckedPolicy.PUBLISH).state())
                .isEqualTo(ModerationState.UNCHECKED);
        assertThat(VerdictCombiner.combine(List.of(), UncheckedPolicy.HOLD).state()).isEqualTo(ModerationState.HELD);
    }

    @Test
    void aBlockWinsEvenNextToStubs() {
        assertThat(VerdictCombiner.combine(List.of(CheckResult.block("word-filter", "PROFANITY"),
                CheckResult.skipped("chatkhu")), UncheckedPolicy.PUBLISH).state()).isEqualTo(ModerationState.REJECTED);
    }
}
