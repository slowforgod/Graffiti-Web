package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Checks a doodle before it is published, outside the page queue (INV-02, ARCHITECTURE.md §4.1 ④).
 *
 * <p>Runs the configured checks for the doodle's kind in order ({@link CheckRunner}). When
 * {@code graffiti.moderation.ml.mode} is ENFORCE, a drawing's {@link MlAnalysis} also counts, except its
 * failures (INV-13); in SHADOW the analysis runs after the doodle is stored and is only recorded. A profanity
 * BLOCK does not stop the run (D-43).
 * {@link VerdictCombiner} makes the state.
 */
@Service
public class ModerationService {

    /**
     * The outcome. {@code detail} is the JSON stored in {@code moderation_detail}; {@code category} is shown to
     * the author on a rejection, or null. {@code ml} is the ENFORCE analysis to record once the doodle has an
     * id, or null. {@code shrink}: a text to publish at the shrink size (D-43).
     */
    public record Verdict(ModerationState state, String detail, String category, MlAnalysis.Result ml,
            boolean shrink) {

        public Verdict(ModerationState state, String detail, String category) {
            this(state, detail, category, null, false);
        }

        /** This verdict with {@code "shrunk": {"fromSize": n}} added to the detail. */
        public Verdict shrunkFrom(int fromSize, ObjectMapper json) {
            try {
                ObjectNode tree = (ObjectNode) json.readTree(detail);
                tree.putObject("shrunk").put("fromSize", fromSize);
                return new Verdict(state, json.writeValueAsString(tree), category, ml, shrink);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ModerationChecks checks;
    private final CheckRunner runner;
    private final MlAnalysis ml;
    private final ObjectMapper json;
    private final GraffitiProperties.UncheckedPolicy unchecked;

    @Autowired
    ModerationService(ModerationChecks checks, CheckRunner runner, MlAnalysis ml, ObjectMapper json,
            GraffitiProperties props) {
        this(checks, runner, ml, json, props.moderation().unchecked());
    }

    ModerationService(ModerationChecks checks, CheckRunner runner, MlAnalysis ml, ObjectMapper json,
            GraffitiProperties.UncheckedPolicy unchecked) {
        this.checks = checks;
        this.runner = runner;
        this.ml = ml;
        this.json = json;
        this.unchecked = unchecked;
    }

    /** {@code payload} is the checked payload record of the doodle. */
    public Verdict moderate(String kind, Object payload) {
        ModerationInput input = input(kind, payload);
        List<CheckResult> results = runner.inOrder(checks.forKind(kind), input);

        MlAnalysis.Result analysis = ml.enforces(kind) ? ml.analyze(input.payload()) : null;
        List<CheckResult> enforced = new ArrayList<>(results);
        if (analysis != null && analysis.enforced() != null) {
            enforced.add(analysis.enforced());
        }

        VerdictCombiner.Decision decision = VerdictCombiner.combine(enforced, unchecked, input.isText());
        return new Verdict(decision.state(), detail(results, analysis, decision), decision.category(), analysis,
                decision.shrink());
    }

    private ModerationInput input(String kind, Object payload) {
        JsonNode tree = json.valueToTree(payload);
        String text = "TEXT".equals(kind) ? tree.path("text").asText(null) : null;
        return new ModerationInput(kind, text, tree.toString());
    }

    // The stored detail names checks and outcomes only, never the text or a provider's raw answer (SEC-05).
    private String detail(List<CheckResult> results, MlAnalysis.Result analysis, VerdictCombiner.Decision decision) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checks", results.stream().map(ModerationService::entry).toList());
        if (analysis != null) {
            Map<String, Object> entry = entry(analysis.summary());
            entry.put("mode", GraffitiProperties.MlMode.ENFORCE.name());
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
