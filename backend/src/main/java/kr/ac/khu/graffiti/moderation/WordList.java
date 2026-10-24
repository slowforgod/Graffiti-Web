package kr.ac.khu.graffiti.moderation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banned words with their category, and allowed words that contain a banned one ("시발점"). Parsed from the
 * files under {@code resources/moderation/}: one entry per line, {@code #} starts a comment, a banned line may
 * end with a category after whitespace (default {@code PROFANITY}). Entries are normalized like the text. Pure.
 */
public record WordList(Map<String, String> banned, List<String> allowed) {

    public static final String DEFAULT_CATEGORY = "PROFANITY";

    public static WordList parse(String bannedFile, String allowedFile) {
        Map<String, String> banned = new LinkedHashMap<>();
        for (String line : entries(bannedFile)) {
            String[] parts = line.split("\\s+");
            String word = TextNormalizer.normalize(parts[0]);
            String category = parts.length > 1 ? parts[1] : DEFAULT_CATEGORY;
            if (parts.length > 2 || !category.matches("[A-Z_]+")) {
                throw new IllegalArgumentException("bad banned-words line: " + line);
            }
            if (!word.isEmpty()) {
                banned.put(word, category);
            }
        }
        List<String> allowed = entries(allowedFile).stream().map(TextNormalizer::normalize)
                .filter(w -> !w.isEmpty()).toList();
        return new WordList(Map.copyOf(banned), allowed);
    }

    public boolean isEmpty() {
        return banned.isEmpty();
    }

    /** The category of the first banned word in {@code text} that is not inside an allowed word, or null. */
    public String match(String text) {
        String normalized = TextNormalizer.normalize(text);
        boolean[] excused = new boolean[normalized.length()];
        for (String word : allowed) {
            for (int at = normalized.indexOf(word); at >= 0; at = normalized.indexOf(word, at + 1)) {
                Arrays.fill(excused, at, at + word.length(), true);
            }
        }
        for (Map.Entry<String, String> entry : banned.entrySet()) {
            String word = entry.getKey();
            for (int at = normalized.indexOf(word); at >= 0; at = normalized.indexOf(word, at + 1)) {
                if (!allExcused(excused, at, at + word.length())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static boolean allExcused(boolean[] excused, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!excused[i]) {
                return false;
            }
        }
        return true;
    }

    private static List<String> entries(String file) {
        List<String> out = new ArrayList<>();
        for (String raw : file.split("\\R")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }
}
