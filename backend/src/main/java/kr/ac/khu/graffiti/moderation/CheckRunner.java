package kr.ac.khu.graffiti.moderation;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.stereotype.Component;

/**
 * Runs checks in order, each under the moderation timeout, and stops at the first BLOCK that rejects (D-19).
 * Profanity does not stop the run, so a later check can still find what rejects (D-43). Shared by the
 * doodle's own checks ({@link ModerationService}) and the checks of the handwriting the ML server read
 * ({@link MlAnalysis}), so both follow the same rules.
 */
@Component
class CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final Duration timeout;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    CheckRunner(GraffitiProperties props) {
        this(props.moderation().timeout());
    }

    CheckRunner(Duration timeout) {
        this.timeout = timeout;
    }

    List<CheckResult> inOrder(List<ModerationCheck> checks, ModerationInput input) {
        List<CheckResult> results = new ArrayList<>();
        for (ModerationCheck check : checks) {
            CheckResult result = run(check, input);
            results.add(result);
            if (result.rejects()) {
                break;
            }
        }
        return results;
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    private CheckResult run(ModerationCheck check, ModerationInput input) {
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
}
