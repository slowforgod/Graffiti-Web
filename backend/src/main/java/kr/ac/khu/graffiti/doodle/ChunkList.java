package kr.ac.khu.graffiti.doodle;

import java.util.List;
import java.util.TreeSet;

/**
 * The {@code chunks} query parameter of the chunk lookup (API.md §2): comma-separated chunk indices,
 * each {@code 0 <= i < chunkCount}, at most {@code maxChunks} of them (D-30, SEC-09). Duplicates are
 * dropped and the result is sorted.
 */
public final class ChunkList {

    private ChunkList() {
    }

    /** @throws IllegalArgumentException with the failed rule as its message */
    public static List<Integer> parse(String raw, int chunkCount, int maxChunks) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("required");
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > maxChunks) {
            throw new IllegalArgumentException("too_many");
        }
        TreeSet<Integer> chunks = new TreeSet<>();
        for (String part : parts) {
            int index = parseIndex(part.strip());
            if (index < 0 || index >= chunkCount) {
                throw new IllegalArgumentException("out_of_range");
            }
            chunks.add(index);
        }
        return List.copyOf(chunks);
    }

    private static int parseIndex(String part) {
        // Digits only: no sign, no blanks, and short enough to fit in an int.
        if (part.isEmpty() || part.length() > 9 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("not_a_number");
        }
        return Integer.parseInt(part);
    }
}
