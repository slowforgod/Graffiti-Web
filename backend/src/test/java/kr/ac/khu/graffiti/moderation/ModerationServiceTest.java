package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import kr.ac.khu.graffiti.config.GraffitiProperties.MlMode;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ModerationServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Map<String, Object> TEXT = Map.of("v", 1, "text", "비밀 글자", "c", 0);

    private static final Map<String, Object> DRAWING = Map.of("v", 1,
            "strokes", List.of(Map.of("c", 0, "w", 4, "p", List.of(10, 10, 40, 40))));
    private static final MlAnswer FLAGGED = new MlAnswer(MlAnswer.Outcome.OK, "clip-zs-1", MlAnswer.Label.FLAGGED,
            Map.of("SEXUAL", 0.9, "HATE", 0.1), "", 0.0, 100);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> called = new CopyOnWriteArrayList<>();
    private final CheckRunner runner = new CheckRunner(TIMEOUT);
    private final MlModerationProvider provider = Mockito.mock(MlModerationProvider.class);
    private ModerationService service;

    @AfterEach
    void stop() {
        runner.stop();
    }

    @Test
    void runsTheTextChecksInOrderAndPassesWhenAllPass() throws Exception {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", CheckResult::pass),
                check("b", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        assertThat(called).containsExactly("a", "b");
        assertThat(json.readTree(v.detail()).get("checks").findValuesAsText("outcome")).containsExactly("PASS", "PASS");
    }

    @Test
    void stopsAtTheFirstBlockThatRejects() {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", n -> CheckResult.block(n, "HATE")),
                check("b", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(v.state()).isEqualTo(ModerationState.REJECTED);
        assertThat(v.category()).isEqualTo("HATE");
        assertThat(called).containsExactly("a");
    }

    @Test
    void profanityGoesOnToTheNextChecksAndShrinksATextThatPassesThem() {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(
                check("a", n -> CheckResult.block(n, "PROFANITY")), check("b", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(called).containsExactly("a", "b");
        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        assertThat(v.shrink()).isTrue();
        assertThat(v.category()).isNull();
    }

    @Test
    void profanityThenARejectingBlockRejectsWithTheLaterCategory() {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(
                check("a", n -> CheckResult.block(n, "PROFANITY")), check("b", n -> CheckResult.block(n,
                        "PERSONAL_INFO")), check("c", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(called).containsExactly("a", "b");
        assertThat(v.state()).isEqualTo(ModerationState.REJECTED);
        assertThat(v.category()).isEqualTo("PERSONAL_INFO");
        assertThat(v.shrink()).isFalse();
    }

    @Test
    void aSlowThrowingOrMalformedCheckIsPublishedUnchecked() throws Exception {
        ModerationCheck slow = check("slow", n -> {
            sleep(TIMEOUT.multipliedBy(5));
            return CheckResult.pass(n);
        });
        ModerationCheck throwing = check("throwing", n -> {
            throw new IllegalStateException("비밀 글자");
        });
        ModerationCheck wrongName = check("named", n -> CheckResult.pass("other"));
        ModerationCheck freeText = check("chatty", n -> CheckResult.block(n, "욕설입니다 비밀 글자"));
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(slow, throwing, wrongName, freeText));

        long started = System.nanoTime();
        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(4));
        assertThat(v.state()).isEqualTo(ModerationState.UNCHECKED);
        JsonNode checks = json.readTree(v.detail()).get("checks");
        assertThat(checks.findValuesAsText("outcome")).containsExactly("ERROR", "ERROR", "ERROR", "ERROR");
        assertThat(checks.findValuesAsText("note")).containsExactly("TIMEOUT", "EXCEPTION", "BAD_RESULT", "BAD_RESULT");
        assertThat(v.detail()).doesNotContain("비밀");
    }

    @Test
    void stubsAloneMakeAnUncheckedDoodleThatThePolicyPublishesOrHolds() throws Exception {
        List<ModerationCheck> stubs = List.of(new StubCheck("word-filter"), new StubCheck("chatkhu"));

        service = service(UncheckedPolicy.PUBLISH, MlMode.OFF, stubs);
        assertThat(service.moderate("TEXT", TEXT).state()).isEqualTo(ModerationState.UNCHECKED);

        service = service(UncheckedPolicy.HOLD, MlMode.OFF, stubs);
        ModerationService.Verdict held = service.moderate("TEXT", TEXT);
        assertThat(held.state()).isEqualTo(ModerationState.HELD);
        assertThat(json.readTree(held.detail()).get("heldAsUnchecked").asBoolean()).isTrue();
    }

    @Test
    void inShadowTheVerdictDoesNotWaitForTheMlServer() throws Exception {
        Mockito.when(provider.moderate(Mockito.any(), Mockito.any())).thenReturn(FLAGGED);
        service = service(UncheckedPolicy.HOLD, MlMode.SHADOW, List.of(check("a", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("DRAWING", DRAWING);

        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        assertThat(v.ml()).isNull();
        assertThat(json.readTree(v.detail()).has("ml")).isFalse();
        Mockito.verifyNoInteractions(provider);
    }

    @Test
    void inEnforceAFlaggedDrawingIsRejectedAndTheAnalysisKeptForTheRecord() throws Exception {
        Mockito.when(provider.moderate(Mockito.any(), Mockito.any())).thenReturn(FLAGGED);
        service = service(UncheckedPolicy.HOLD, MlMode.ENFORCE, List.of(check("a", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("DRAWING", DRAWING);

        assertThat(v.state()).isEqualTo(ModerationState.REJECTED);
        assertThat(v.category()).isEqualTo("SEXUAL");
        assertThat(v.ml().answer()).isEqualTo(FLAGGED);
        JsonNode recorded = json.readTree(v.detail()).get("ml");
        assertThat(recorded.get("outcome").asText()).isEqualTo("BLOCK");
        assertThat(recorded.get("mode").asText()).isEqualTo("ENFORCE");
    }

    @Test
    void inEnforceAnMlFailureNeverHolds() throws Exception {
        Mockito.when(provider.moderate(Mockito.any(), Mockito.any()))
                .thenReturn(MlAnswer.failed(MlAnswer.Outcome.TIMEOUT));
        service = service(UncheckedPolicy.HOLD, MlMode.ENFORCE, List.of(check("a", CheckResult::pass)));

        ModerationService.Verdict v = service.moderate("DRAWING", DRAWING);

        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        JsonNode recorded = json.readTree(v.detail()).get("ml");
        assertThat(recorded.get("outcome").asText()).isEqualTo("ERROR");
        assertThat(recorded.get("note").asText()).isEqualTo("TIMEOUT");
    }

    @Test
    void textDoodlesAndMlOffNeverCallTheMlServer() throws Exception {
        service = service(UncheckedPolicy.HOLD, MlMode.ENFORCE, List.of(check("a", CheckResult::pass)));
        assertThat(service.moderate("TEXT", TEXT).ml()).isNull();

        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", CheckResult::pass)));
        ModerationService.Verdict v = service.moderate("DRAWING", DRAWING);

        assertThat(json.readTree(v.detail()).has("ml")).isFalse();
        Mockito.verifyNoInteractions(provider);
    }

    @Test
    void textChecksSeeTheTextAndDrawingChecksThePayload() {
        List<ModerationInput> seen = new CopyOnWriteArrayList<>();
        ModerationCheck recorder = new ModerationCheck() {
            @Override
            public String name() {
                return "a";
            }

            @Override
            public CheckResult check(ModerationInput input) {
                seen.add(input);
                return CheckResult.pass("a");
            }
        };
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(recorder));

        service.moderate("TEXT", TEXT);
        service.moderate("DRAWING", Map.of("v", 1));

        assertThat(seen.get(0).text()).isEqualTo("비밀 글자");
        assertThat(seen.get(1).text()).isNull();
        assertThat(seen.get(1).payload()).isEqualTo("{\"v\":1}");
    }

    private ModerationService service(UncheckedPolicy policy, MlMode mode, List<ModerationCheck> checks) {
        MlAnalysis ml = new MlAnalysis(mode, new DoodleRenderer(List.of("#000000"), 1024), provider, List.of(),
                runner, Mockito.mock(MlPredictionRecorder.class), 4);
        return new ModerationService(new ModerationChecks(checks, checks), runner, ml, json, policy);
    }

    private ModerationCheck check(String name, Function<String, CheckResult> answer) {
        return new ModerationCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CheckResult check(ModerationInput input) {
                called.add(name);
                return answer.apply(name);
            }
        };
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
