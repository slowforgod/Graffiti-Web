package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.config.GraffitiProperties.MlMode;
import kr.ac.khu.graffiti.config.GraffitiProperties.UncheckedPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModerationServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Map<String, Object> TEXT = Map.of("v", 1, "text", "비밀 글자", "c", 0);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> called = new CopyOnWriteArrayList<>();
    private ModerationService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void runsTheTextChecksInOrderAndPassesWhenAllPass() throws Exception {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", CheckResult::pass),
                check("b", CheckResult::pass)), new StubCheck("ml"));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        assertThat(called).containsExactly("a", "b");
        assertThat(json.readTree(v.detail()).get("checks").findValuesAsText("outcome")).containsExactly("PASS", "PASS");
    }

    @Test
    void stopsAtTheFirstBlock() {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", n -> CheckResult.block(n, "PROFANITY")),
                check("b", CheckResult::pass)), new StubCheck("ml"));

        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(v.state()).isEqualTo(ModerationState.REJECTED);
        assertThat(v.category()).isEqualTo("PROFANITY");
        assertThat(called).containsExactly("a");
    }

    @Test
    void aSlowThrowingOrMalformedCheckHolds() throws Exception {
        ModerationCheck slow = check("slow", n -> {
            sleep(TIMEOUT.multipliedBy(5));
            return CheckResult.pass(n);
        });
        ModerationCheck throwing = check("throwing", n -> {
            throw new IllegalStateException("비밀 글자");
        });
        ModerationCheck wrongName = check("named", n -> CheckResult.pass("other"));
        ModerationCheck freeText = check("chatty", n -> CheckResult.block(n, "욕설입니다 비밀 글자"));
        service = service(UncheckedPolicy.PUBLISH, MlMode.OFF, List.of(slow, throwing, wrongName, freeText),
                new StubCheck("ml"));

        long started = System.nanoTime();
        ModerationService.Verdict v = service.moderate("TEXT", TEXT);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(4));
        assertThat(v.state()).isEqualTo(ModerationState.HELD);
        JsonNode checks = json.readTree(v.detail()).get("checks");
        assertThat(checks.findValuesAsText("outcome")).containsExactly("ERROR", "ERROR", "ERROR", "ERROR");
        assertThat(checks.findValuesAsText("note")).containsExactly("TIMEOUT", "EXCEPTION", "BAD_RESULT", "BAD_RESULT");
        assertThat(v.detail()).doesNotContain("비밀");
    }

    @Test
    void stubsAloneMakeAnUncheckedDoodleThatThePolicyPublishesOrHolds() throws Exception {
        List<ModerationCheck> stubs = List.of(new StubCheck("word-filter"), new StubCheck("chatkhu"));

        service = service(UncheckedPolicy.PUBLISH, MlMode.OFF, stubs, new StubCheck("ml"));
        assertThat(service.moderate("TEXT", TEXT).state()).isEqualTo(ModerationState.UNCHECKED);
        service.stop();

        service = service(UncheckedPolicy.HOLD, MlMode.OFF, stubs, new StubCheck("ml"));
        ModerationService.Verdict held = service.moderate("TEXT", TEXT);
        assertThat(held.state()).isEqualTo(ModerationState.HELD);
        assertThat(json.readTree(held.detail()).get("heldAsUnchecked").asBoolean()).isTrue();
    }

    @Test
    void inShadowTheMlAnswerIsOnlyRecorded() throws Exception {
        ModerationCheck ml = check("ml", n -> CheckResult.block(n, "SEXUAL"));
        service = service(UncheckedPolicy.HOLD, MlMode.SHADOW, List.of(check("a", CheckResult::pass)), ml);

        ModerationService.Verdict v = service.moderate("DRAWING", Map.of("v", 1));

        assertThat(v.state()).isEqualTo(ModerationState.PASSED);
        JsonNode recorded = json.readTree(v.detail()).get("ml");
        assertThat(recorded.get("outcome").asText()).isEqualTo("BLOCK");
        assertThat(recorded.get("mode").asText()).isEqualTo("SHADOW");
    }

    @Test
    void inEnforceTheMlAnswerCountsButItsFailuresNeverHold() {
        service = service(UncheckedPolicy.HOLD, MlMode.ENFORCE, List.of(check("a", CheckResult::pass)),
                check("ml", n -> CheckResult.block(n, "SEXUAL")));
        assertThat(service.moderate("DRAWING", Map.of("v", 1)).state()).isEqualTo(ModerationState.REJECTED);
        service.stop();

        service = service(UncheckedPolicy.HOLD, MlMode.ENFORCE, List.of(check("a", CheckResult::pass)),
                check("ml", n -> {
                    throw new IllegalStateException("down");
                }));
        assertThat(service.moderate("DRAWING", Map.of("v", 1)).state()).isEqualTo(ModerationState.PASSED);
    }

    @Test
    void mlOffIsNotCalled() throws Exception {
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(check("a", CheckResult::pass)),
                check("ml", CheckResult::pass));

        ModerationService.Verdict v = service.moderate("DRAWING", Map.of("v", 1));

        assertThat(called).containsExactly("a");
        assertThat(json.readTree(v.detail()).has("ml")).isFalse();
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
        service = service(UncheckedPolicy.HOLD, MlMode.OFF, List.of(recorder), new StubCheck("ml"));

        service.moderate("TEXT", TEXT);
        service.moderate("DRAWING", Map.of("v", 1));

        assertThat(seen.get(0).text()).isEqualTo("비밀 글자");
        assertThat(seen.get(1).text()).isNull();
        assertThat(seen.get(1).payload()).isEqualTo("{\"v\":1}");
    }

    private ModerationService service(UncheckedPolicy policy, MlMode mode, List<ModerationCheck> checks,
            ModerationCheck ml) {
        GraffitiProperties.Moderation config = new GraffitiProperties.Moderation(TIMEOUT, policy, List.of(), List.of(),
                new GraffitiProperties.Ml(mode, "http://ml:8000", TIMEOUT, null));
        return new ModerationService(new ModerationChecks(checks, checks, ml), json, config);
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
