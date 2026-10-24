package kr.ac.khu.graffiti.moderation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The checks to run per kind, in configured order. A name with no {@link ModerationCheck} bean runs as a
 * {@link StubCheck}; an unknown name fails startup so a typo cannot silently drop a check.
 */
@Component
public class ModerationChecks {

    /** Every check the pipeline knows (D-19, D-34). {@code ml} runs by {@code graffiti.moderation.ml.mode}. */
    public static final Set<String> KNOWN = Set.of("word-filter", "chatkhu", "openai-text", "openai-image", "ml");

    public static final String ML = "ml";

    private static final Logger log = LoggerFactory.getLogger(ModerationChecks.class);

    private final List<ModerationCheck> text;
    private final List<ModerationCheck> drawing;
    private final ModerationCheck ml;

    @Autowired
    ModerationChecks(List<ModerationCheck> beans, GraffitiProperties props) {
        Map<String, ModerationCheck> real = beans.stream()
                .collect(Collectors.toMap(ModerationCheck::name, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("two moderation checks named " + a.name());
                }, LinkedHashMap::new));
        real.keySet().forEach(ModerationChecks::requireKnown);
        GraffitiProperties.Moderation m = props.moderation();
        this.text = resolve(m.textChecks(), real);
        this.drawing = resolve(m.drawingChecks(), real);
        this.ml = real.getOrDefault(ML, new StubCheck(ML));

        List<String> stubbed = Stream.of(text, drawing, List.of(ml)).flatMap(List::stream)
                .filter(StubCheck.class::isInstance).map(ModerationCheck::name).distinct().toList();
        if (!stubbed.isEmpty()) {
            log.warn("Moderation checks running as stubs (nothing checked): {}; unchecked doodles are {}",
                    stubbed, m.unchecked() == GraffitiProperties.UncheckedPolicy.HOLD ? "held" : "published");
        }
    }

    ModerationChecks(List<ModerationCheck> text, List<ModerationCheck> drawing, ModerationCheck ml) {
        this.text = List.copyOf(text);
        this.drawing = List.copyOf(drawing);
        this.ml = ml;
    }

    public List<ModerationCheck> forKind(String kind) {
        return "TEXT".equals(kind) ? text : drawing;
    }

    public ModerationCheck ml() {
        return ml;
    }

    private static List<ModerationCheck> resolve(List<String> names, Map<String, ModerationCheck> real) {
        return names.stream().map(name -> {
            requireKnown(name);
            if (ML.equals(name)) {
                throw new IllegalStateException("ml is set by graffiti.moderation.ml.mode, not a check list");
            }
            return real.getOrDefault(name, new StubCheck(name));
        }).toList();
    }

    private static void requireKnown(String name) {
        if (!KNOWN.contains(name)) {
            throw new IllegalStateException("unknown moderation check: " + name + " (known: " + KNOWN + ")");
        }
    }
}
