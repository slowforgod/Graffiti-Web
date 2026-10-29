package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import kr.ac.khu.graffiti.config.GraffitiProperties.MlMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MlAnalysisTest {

    private static final String DRAWING = "{\"v\":1,\"strokes\":[{\"c\":0,\"w\":4,\"p\":[10,10,40,40]}]}";

    private final MlModerationProvider provider = mock(MlModerationProvider.class);
    private final MlPredictionRecorder recorder = mock(MlPredictionRecorder.class);
    private final CheckRunner runner = new CheckRunner(Duration.ofMillis(500));
    private final List<ModerationInput> seen = new CopyOnWriteArrayList<>();
    private MlAnalysis analysis;

    @AfterEach
    void stop() {
        runner.stop();
        if (analysis != null) {
            analysis.stop();
        }
    }

    @Test
    void noHandwritingMeansTheTextChecksAreNotCalled() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.SAFE, ""));
        analysis = analysis(MlMode.SHADOW, 4, CheckResult::pass);

        MlAnalysis.Result r = analysis.analyze(DRAWING);

        assertThat(r.ocrOutcome()).isEqualTo(OcrTextOutcome.NONE);
        assertThat(seen).isEmpty();
        assertThat(r.summary().outcome()).isEqualTo(CheckOutcome.PASS);
    }

    @Test
    void theHandwritingGoesThroughTheTextChecks() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.SAFE, "읽은 글자"));
        analysis = analysis(MlMode.SHADOW, 4, CheckResult::pass);

        MlAnalysis.Result r = analysis.analyze(DRAWING);

        assertThat(seen).singleElement().satisfies(input -> {
            assertThat(input.kind()).isEqualTo("TEXT");
            assertThat(input.text()).isEqualTo("읽은 글자");
        });
        assertThat(r.ocrOutcome()).isEqualTo(OcrTextOutcome.PASS);
    }

    @Test
    void blockedHandwritingBlocksProfanityIncludedAndDoubtIsReviewed() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.SAFE, "읽은 글자"));

        MlAnalysis.Result sexual = analysis(MlMode.ENFORCE, 4, n -> CheckResult.block(n, "SEXUAL")).analyze(DRAWING);
        assertThat(sexual.ocrOutcome()).isEqualTo(OcrTextOutcome.BLOCK);
        assertThat(sexual.ocrCategory()).isEqualTo("SEXUAL");
        assertThat(sexual.enforced()).isEqualTo(CheckResult.block("ml", "SEXUAL"));

        MlAnalysis.Result profanity = analysis(MlMode.ENFORCE, 4, n -> CheckResult.block(n, "PROFANITY"))
                .analyze(DRAWING);
        assertThat(profanity.enforced()).isEqualTo(CheckResult.block("ml", "PROFANITY"));

        MlAnalysis.Result unsure = analysis(MlMode.ENFORCE, 4, n -> CheckResult.review(n, "PERSONAL_INFO"))
                .analyze(DRAWING);
        assertThat(unsure.ocrOutcome()).isEqualTo(OcrTextOutcome.REVIEW);
        assertThat(unsure.enforced()).isEqualTo(CheckResult.review("ml", "PERSONAL_INFO"));
    }

    @Test
    void aFlaggedImageBlocksWithItsTopCategory() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.FLAGGED, ""));
        analysis = analysis(MlMode.ENFORCE, 4, CheckResult::pass);

        assertThat(analysis.analyze(DRAWING).enforced()).isEqualTo(CheckResult.block("ml", "HATE"));
    }

    @Test
    void failuresOnThePathAreErrorsThatEnforceIgnores() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.SAFE, "읽은 글자"));
        MlAnalysis.Result checkFailed = analysis(MlMode.ENFORCE, 4, n -> CheckResult.error(n, "TIMEOUT"))
                .analyze(DRAWING);
        assertThat(checkFailed.ocrOutcome()).isEqualTo(OcrTextOutcome.ERROR);
        assertThat(checkFailed.summary().outcome()).isEqualTo(CheckOutcome.ERROR);
        assertThat(checkFailed.enforced()).isNull();

        when(provider.moderate(any(), any())).thenReturn(MlAnswer.failed(MlAnswer.Outcome.TIMEOUT));
        MlAnalysis.Result serverFailed = analysis(MlMode.ENFORCE, 4, CheckResult::pass).analyze(DRAWING);
        assertThat(serverFailed.answer().outcome()).isEqualTo(MlAnswer.Outcome.TIMEOUT);
        assertThat(serverFailed.ocrOutcome()).isNull();
        assertThat(serverFailed.enforced()).isNull();
    }

    @Test
    void aDrawingThatCannotBeRenderedIsAnErrorWithoutCallingTheServer() {
        analysis = analysis(MlMode.SHADOW, 4, CheckResult::pass);

        MlAnalysis.Result r = analysis.analyze("{\"v\":1,\"text\":\"비밀\"}");

        assertThat(r.answer().outcome()).isEqualTo(MlAnswer.Outcome.ERROR);
        verifyNoInteractions(provider);
    }

    @Test
    void stubTextChecksLeaveTheHandwritingSkipped() {
        when(provider.moderate(any(), any())).thenReturn(answer(MlAnswer.Label.SAFE, "읽은 글자"));
        analysis = new MlAnalysis(MlMode.SHADOW, renderer(), provider, List.of(new StubCheck("chatkhu")), runner,
                recorder, 4);

        assertThat(analysis.analyze(DRAWING).ocrOutcome()).isEqualTo(OcrTextOutcome.SKIPPED);
    }

    @Test
    void shadowRecordsInTheBackgroundAndSkipsWhenFull() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        when(provider.moderate(any(), any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return answer(MlAnswer.Label.SAFE, "");
        });
        analysis = analysis(MlMode.SHADOW, 1, CheckResult::pass);

        analysis.afterStored(7, "DRAWING", DRAWING, null);
        analysis.afterStored(8, "DRAWING", DRAWING, null);
        release.countDown();

        ArgumentCaptor<MlAnalysis.Result> recorded = ArgumentCaptor.forClass(MlAnalysis.Result.class);
        verify(recorder, timeout(2000)).record(eq(7L), eq(MlMode.SHADOW), recorded.capture());
        assertThat(recorded.getValue().answer().label()).isEqualTo(MlAnswer.Label.SAFE);
        Thread.sleep(200);
        verify(recorder, never()).record(eq(8L), any(), any());

        analysis.afterStored(9, "DRAWING", DRAWING, null);
        verify(recorder, timeout(2000)).record(eq(9L), eq(MlMode.SHADOW), any());
    }

    @Test
    void enforceRecordsTheResultTheVerdictUsedAndTextOrOffRecordNothing() {
        MlAnalysis.Result used = MlAnalysis.Result.failed(MlAnswer.Outcome.ERROR);
        analysis = analysis(MlMode.ENFORCE, 4, CheckResult::pass);
        analysis.afterStored(3, "DRAWING", DRAWING, used);
        verify(recorder).record(3L, MlMode.ENFORCE, used);

        analysis.afterStored(4, "TEXT", "{}", null);
        analysis(MlMode.OFF, 4, CheckResult::pass).afterStored(5, "DRAWING", DRAWING, null);
        verify(recorder, never()).record(eq(4L), any(), any());
        verify(recorder, never()).record(eq(5L), any(), any());
        verifyNoInteractions(provider);
    }

    @Test
    void onlyEnforceMakesADrawingWait() {
        assertThat(analysis(MlMode.ENFORCE, 4, CheckResult::pass).enforces("DRAWING")).isTrue();
        assertThat(analysis(MlMode.ENFORCE, 4, CheckResult::pass).enforces("TEXT")).isFalse();
        assertThat(analysis(MlMode.SHADOW, 4, CheckResult::pass).enforces("DRAWING")).isFalse();
        verify(recorder, never()).record(anyLong(), any(), any());
    }

    private MlAnalysis analysis(MlMode mode, int maxInFlight, Function<String, CheckResult> textAnswer) {
        ModerationCheck text = new ModerationCheck() {
            @Override
            public String name() {
                return "word-filter";
            }

            @Override
            public CheckResult check(ModerationInput input) {
                seen.add(input);
                return textAnswer.apply(name());
            }
        };
        return new MlAnalysis(mode, renderer(), provider, List.of(text), runner, recorder, maxInFlight);
    }

    private static DoodleRenderer renderer() {
        return new DoodleRenderer(List.of("#000000"), 1024);
    }

    private static MlAnswer answer(MlAnswer.Label label, String ocrText) {
        return new MlAnswer(MlAnswer.Outcome.OK, "clip-zs-1", label, Map.of("SEXUAL", 0.2, "HATE", 0.7), ocrText,
                ocrText.isEmpty() ? 0.0 : 0.8, 120);
    }
}
