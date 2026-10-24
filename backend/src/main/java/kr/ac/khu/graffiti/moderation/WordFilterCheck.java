package kr.ac.khu.graffiti.moderation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The first filter for text (D-19 ①, FILTER.md §3): a banned word blocks without calling any AI. The lists are
 * {@code resources/moderation/banned-words.txt} and {@code allowed-words.txt}; while the banned list is empty
 * the check is {@link CheckOutcome#SKIPPED}, so an empty list never counts as "checked".
 */
@Component
class WordFilterCheck implements ModerationCheck {

    static final String NAME = "word-filter";

    private final WordList words;

    WordFilterCheck() {
        this(WordList.parse(read("moderation/banned-words.txt"), read("moderation/allowed-words.txt")));
    }

    WordFilterCheck(WordList words) {
        this.words = words;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        if (!input.isText() || words.isEmpty()) {
            return CheckResult.skipped(NAME);
        }
        String category = words.match(input.text());
        return category == null ? CheckResult.pass(NAME) : CheckResult.block(NAME, category);
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing " + path, e);
        }
    }
}
