package kr.ac.khu.graffiti.moderation;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.config.GraffitiProperties.MlMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The ML path of a drawing (ARCHITECTURE.md §4.7, specs/07b): render it, ask the ML server, and send the
 * handwriting it read through the text checks. Runs outside the page queue (INV-02).
 *
 * <p>By {@code graffiti.moderation.ml.mode}: OFF does nothing. SHADOW runs after the doodle is stored, in the
 * background, at most {@code max-in-flight} at once, and only records the result (INV-13). ENFORCE runs before
 * the verdict ({@link ModerationService}) and its result is recorded once the doodle has an id. A failure on
 * this path never holds a doodle.
 */
@Service
public class MlAnalysis {

    private static final Logger log = LoggerFactory.getLogger(MlAnalysis.class);
    private static final String CHECK = ModerationChecks.ML;
    private static final String PROFANITY = "PROFANITY";

    /**
     * One analysis. {@code ocrOutcome} is null when the ML server gave no answer; {@code ocrCategory} is the
     * category of the blocking or reviewing text check.
     */
    public record Result(MlAnswer answer, OcrTextOutcome ocrOutcome, String ocrCategory) {

        static Result failed(MlAnswer.Outcome outcome) {
            return new Result(MlAnswer.failed(outcome), null, null);
        }

        /**
         * What the analysis says as one check (07b decisions, 2026-10-28): a FLAGGED image or blocked
         * handwriting of categories 2-4 blocks; handwriting the checks were unsure of, or profanity, is
         * reviewed; a failure anywhere on the path is an ERROR.
         */
        public CheckResult summary() {
            if (!answer.ok()) {
                return CheckResult.error(CHECK, answer.outcome().name());
            }
            if (answer.label() == MlAnswer.Label.FLAGGED) {
                return CheckResult.block(CHECK, answer.topCategory());
            }
            return switch (ocrOutcome) {
                case BLOCK -> PROFANITY.equals(ocrCategory)
                        ? CheckResult.review(CHECK, ocrCategory) : CheckResult.block(CHECK, ocrCategory);
                case REVIEW -> CheckResult.review(CHECK, ocrCategory);
                case ERROR -> CheckResult.error(CHECK, "OCR_TEXT_ERROR");
                case SKIPPED -> CheckResult.skipped(CHECK);
                case NONE, PASS -> CheckResult.pass(CHECK);
            };
        }

        /** The summary as ENFORCE counts it: null for a failure, which never holds a doodle (INV-13). */
        public CheckResult enforced() {
            CheckResult summary = summary();
            return summary.outcome() == CheckOutcome.ERROR ? null : summary;
        }
    }

    private final MlMode mode;
    private final DoodleRenderer renderer;
    private final MlModerationProvider provider;
    private final List<ModerationCheck> textChecks;
    private final CheckRunner runner;
    private final MlPredictionRecorder recorder;
    private final Semaphore slots;
    private final int maxInFlight;
    private final AtomicLong skipped = new AtomicLong();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    MlAnalysis(GraffitiProperties props, MlModerationProvider provider, ModerationChecks checks, CheckRunner runner,
            MlPredictionRecorder recorder) {
        this(props.moderation().ml().mode(),
                new DoodleRenderer(props.doodle().palette(), props.moderation().ml().renderMaxSide()),
                provider, checks.forKind("TEXT"), runner, recorder, props.moderation().ml().maxInFlight());
    }

    MlAnalysis(MlMode mode, DoodleRenderer renderer, MlModerationProvider provider, List<ModerationCheck> textChecks,
            CheckRunner runner, MlPredictionRecorder recorder, int maxInFlight) {
        this.mode = mode;
        this.renderer = renderer;
        this.provider = provider;
        this.textChecks = List.copyOf(textChecks);
        this.runner = runner;
        this.recorder = recorder;
        this.maxInFlight = maxInFlight;
        this.slots = new Semaphore(maxInFlight);
    }

    /** True when the verdict of a doodle of this kind must wait for {@link #analyze}. */
    public boolean enforces(String kind) {
        return mode == MlMode.ENFORCE && "DRAWING".equals(kind);
    }

    /** {@code payload} is the drawing's payload JSON. Never throws. */
    public Result analyze(String payload) {
        byte[] png;
        try {
            png = renderer.render(payload);
        } catch (RuntimeException e) {
            log.warn("Could not render a drawing for the ML server: {}", e.getClass().getSimpleName());
            return Result.failed(MlAnswer.Outcome.ERROR);
        }
        MlAnswer answer = provider.moderate(png, UUID.randomUUID().toString());
        if (!answer.ok()) {
            return new Result(answer, null, null);
        }
        if (answer.ocrText().isBlank()) {
            return new Result(answer, OcrTextOutcome.NONE, null);
        }
        List<CheckResult> results = runner.inOrder(textChecks, new ModerationInput("TEXT", answer.ocrText(), null));
        OcrTextOutcome outcome = OcrTextOutcome.of(results);
        return new Result(answer, outcome, category(results, outcome));
    }

    /**
     * Called once a doodle is stored with its id. In SHADOW, starts the analysis of a drawing in the background
     * (or skips it when {@code max-in-flight} are running); in ENFORCE, records {@code enforced}, the result the
     * verdict used. Never throws.
     */
    public void afterStored(long doodleId, String kind, String payload, Result enforced) {
        if (!"DRAWING".equals(kind)) {
            return;
        }
        if (mode == MlMode.ENFORCE) {
            if (enforced != null) {
                recorder.record(doodleId, MlMode.ENFORCE, enforced);
            }
            return;
        }
        if (mode != MlMode.SHADOW) {
            return;
        }
        if (!slots.tryAcquire()) {
            // Skipped drawings only shrink the evaluation sample; a queue would back up the ML server.
            log.info("ML shadow analysis skipped: {} running ({} skipped so far)", maxInFlight,
                    skipped.incrementAndGet());
            return;
        }
        try {
            pool.execute(() -> {
                try {
                    recorder.record(doodleId, MlMode.SHADOW, analyze(payload));
                } catch (RuntimeException e) {
                    log.warn("ML shadow analysis of doodle {} failed: {}", doodleId, e.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            slots.release();
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    private static String category(List<CheckResult> results, OcrTextOutcome outcome) {
        CheckOutcome wanted = switch (outcome) {
            case BLOCK -> CheckOutcome.BLOCK;
            case REVIEW -> CheckOutcome.REVIEW;
            default -> null;
        };
        if (wanted == null) {
            return null;
        }
        // A rejecting category outranks profanity found earlier in the run (D-43).
        return results.stream().filter(r -> r.outcome() == wanted)
                .min((a, b) -> Boolean.compare(b.rejects(), a.rejects()))
                .map(CheckResult::category).orElse(null);
    }
}
