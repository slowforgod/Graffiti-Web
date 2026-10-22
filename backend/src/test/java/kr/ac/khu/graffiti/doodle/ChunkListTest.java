package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkListTest {

    private static final int CHUNKS = 144;
    private static final int MAX = 60;

    @Test
    void parsesSortsAndDropsDuplicates() {
        assertThat(ChunkList.parse("17,0,1,17", CHUNKS, MAX)).containsExactly(0, 1, 17);
    }

    @Test
    void acceptsFirstAndLastChunk() {
        assertThat(ChunkList.parse("0,143", CHUNKS, MAX)).containsExactly(0, 143);
    }

    @Test
    void acceptsExactlyTheMaximumCount() {
        String sixty = IntStream.range(0, MAX).mapToObj(Integer::toString).collect(Collectors.joining(","));
        assertThat(ChunkList.parse(sixty, CHUNKS, MAX)).hasSize(MAX);
    }

    @Test
    void rejectsMoreThanTheMaximumCountEvenWithDuplicates() {
        String sixtyOne = IntStream.range(0, MAX + 1).mapToObj(i -> "0").collect(Collectors.joining(","));
        assertRejected(sixtyOne, "too_many");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingValue(String raw) {
        assertRejected(raw, "required");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "144|out_of_range",
            "0,999999999|out_of_range",
            "-1|not_a_number",
            "+1|not_a_number",
            "a|not_a_number",
            "1.5|not_a_number",
            "0,,1|not_a_number",
            "0,|not_a_number",
            "1 OR 1=1|not_a_number",
            "9999999999|not_a_number"})
    void rejectsBadIndices(String raw, String reason) {
        assertRejected(raw, reason);
    }

    private static void assertRejected(String raw, String reason) {
        assertThatThrownBy(() -> ChunkList.parse(raw, CHUNKS, MAX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(reason);
    }
}
