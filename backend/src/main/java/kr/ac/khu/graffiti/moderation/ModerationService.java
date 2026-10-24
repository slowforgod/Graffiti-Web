package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Checks a doodle before it is published, outside the page queue (INV-02, ARCHITECTURE.md §4.1 ④).
 *
 * <p>Runs the configured checks for the doodle's kind in order, each under the moderation timeout, and stops
 * at the first BLOCK. The ML check runs beside them by {@code graffiti.moderation.ml.mode}: in SHADOW its
 * answer is only recorded (INV-13). {@link VerdictCombiner} makes the state.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    /**
     * The outcome. {@code detail} is the JSON stored in {@code moderation_detail}; {@code category} is shown to
     * the author on a rejection, or null.
     */
    public record Verdict(ModerationState state, String detail, String category) {
    }

    private final ModerationChecks checks;
    private final ObjectMapper json;
    private final GraffitiProperties.Moderation config;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    ModerationService(ModerationChecks checks, ObjectMapper json, GraffitiProperties props) {
        this(checks, json, props.moderation());
    }

    ModerationService(ModerationChecks checks, ObjectMapper json, GraffitiProperties.Moderation config) {
        this.checks = checks;
        this.json = json;
        this.config = config;
    }

    /** {@code payload} is the checked payload record of the doodle. */
    public Verdict moderate(String kind, Object payload) {
        ModerationInput input = input(kind, payload);
        List<CheckResult> results = new ArrayList<>();
        for (ModerationCheck check : checks.forKind(kind)) {
            CheckResult result = run(check, input, config.timeout());
            results.add(result);
            if (result.outcome() == CheckOutcome.BLOCK) {
                break;
            }
        }

        GraffitiProperties.MlMode mode = config.ml().mode();
        CheckResult ml = mode == GraffitiProperties.MlMode.OFF ? null
                : run(checks.ml(), input, config.ml().timeout());
        List<CheckResult> enforced = new ArrayList<>(results);
        if (ml != null && mode == GraffitiProperties.MlMode.ENFORCE && ml.outcome() != CheckOutcome.ERROR) {
            // ML failures never hold a doodle (INV-13); only a real answer counts in ENFORCE.
            enforced.add(ml);
        }

        VerdictCombiner.Decision decision = VerdictCombiner.combine(enforced, config.unchecked());
        return new Verdict(decision.state(), detail(results, ml, mode, decision), decision.category());
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    private CheckResult run(ModerationCheck check, ModerationInput input, Duration timeout) {
        Future<CheckResult> future = pool.submit(() -> check.check(input));
        try {
            CheckResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!wellFormed(result, check)) {
                return CheckResult.error(check.name(), "BAD_RESULT");
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            return CheckResult.error(check.name(), "TIMEOUT");
        } catch (ExecutionException e) {
            // No message: it may quote the user's text (SEC-05).
            log.warn("Moderation check {} failed: {}", check.name(), e.getCause().getClass().getSimpleName());
            return CheckResult.error(check.name(), "EXCEPTION");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return CheckResult.error(check.name(), "INTERRUPTED");
        }
    }

    // Category and note go to the DB and the author, so they must be short codes, not free text.
    private static boolean wellFormed(CheckResult result, ModerationCheck check) {
        return result != null && check.name().equals(result.check()) && result.outcome() != null
                && code(result.category()) && code(result.note());
    }

    private static boolean code(String value) {
        return value == null || CODE.matcher(value).matches();
    }

    private ModerationInput input(String kind, Object payload) {
        JsonNode tree = json.valueToTree(payload);
        String text = "TEXT".equals(kind) ? tree.path("text").asText(null) : null;
        return new ModerationInput(kind, text, tree.toString());
    }

    // The stored detail names checks and outcomes only, never the text or a provider's raw answer (SEC-05).
    private String detail(List<CheckResult> results, CheckResult ml, GraffitiProperties.MlMode mode,
            VerdictCombiner.Decision decision) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checks", results.stream().map(ModerationService::entry).toList());
        if (ml != null) {
            Map<String, Object> entry = entry(ml);
            entry.put("mode", mode.name());
            detail.put("ml", entry);
        }
        if (decision.heldAsUnchecked()) {
            detail.put("heldAsUnchecked", true);
        }
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> entry(CheckResult r) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("check", r.check());
        entry.put("outcome", r.outcome().name());
        if (r.category() != null) {
            entry.put("category", r.category());
        }
        if (r.note() != null) {
            entry.put("note", r.note());
        }
        return entry;
    }
}
