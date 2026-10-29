package kr.ac.khu.graffiti.doodle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Box;
import kr.ac.khu.graffiti.doodle.DoodleValidator.DrawingPayload;
import kr.ac.khu.graffiti.doodle.DoodleValidator.InvalidDoodleException;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Limits;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Size;
import kr.ac.khu.graffiti.doodle.DoodleValidator.StrokeInput;
import kr.ac.khu.graffiti.doodle.DoodleValidator.TextPayload;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;
import kr.ac.khu.graffiti.page.CellGeometry;
import kr.ac.khu.graffiti.page.Footprint;
import org.junit.jupiter.api.Test;

class DoodleValidatorTest {

    private static final CellGeometry GEOMETRY = new CellGeometry(7680, 4320, 48);
    private static final Limits LIMITS = new Limits(GEOMETRY, 480, 12, 2, 16,
            List.of(10, 12, 24, 96), 1024, 50);

    @Test
    void drawingBboxIsThePointsWidenedByHalfTheWidth() {
        Validated v = drawing(stroke(3, 8, 470, 10, 490, 12));

        assertThat(v.kind()).isEqualTo("DRAWING");
        assertThat(v.bbox()).isEqualTo(new Box(466, 6, 28, 10));
        // Crosses the border of chunks 0 and 1.
        assertThat(v.chunks()).containsExactly(0, 1);
        assertThat(v.footprint()).isEqualTo(Footprint.ofStrokes(
                List.of(new Footprint.Stroke(8, new int[] {470, 10, 490, 12})), GEOMETRY));
        assertThat(v.payload()).isInstanceOf(DrawingPayload.class);
    }

    @Test
    void storedBboxIsClippedToTheCanvas() {
        Validated corner = drawing(stroke(0, 16, 2, 3));
        assertThat(corner.bbox()).isEqualTo(new Box(0, 0, 10, 11));
        assertThat(corner.chunks()).containsExactly(0);

        Validated far = drawing(stroke(0, 16, 7679, 4319));
        assertThat(far.bbox()).isEqualTo(new Box(7671, 4311, 9, 9));
        assertThat(far.chunks()).containsExactly(8 * 16 + 15);
    }

    @Test
    void theBboxLimitCountsTheStrokeWidthAsTheClientDoes() {
        // 1008 + 16 = 1024 fits, one pixel more does not.
        assertThat(drawing(stroke(0, 16, 100, 100, 1108, 100)).bbox().w()).isEqualTo(1024);
        assertRefused(() -> drawing(stroke(0, 16, 100, 100, 1109, 100)), "strokes", "too_big");
        // The limit holds across strokes too.
        assertRefused(() -> drawing(stroke(0, 2, 100, 100), stroke(0, 2, 1200, 100)), "strokes", "too_big");
    }

    @Test
    void anyWholeWidthFromTheMinimumToTheMaximumIsAllowed() {
        for (int w : new int[] {2, 3, 7, 13, 16}) {
            assertThat(((DrawingPayload) drawing(stroke(0, w, 100, 100)).payload()).strokes().get(0).w()).isEqualTo(w);
        }
    }

    @Test
    void drawingsAreRefusedField() {
        assertRefused(() -> DoodleValidator.drawing(new DrawingPayload(1, List.of()), null, LIMITS), "strokes",
                "required");
        assertRefused(() -> DoodleValidator.drawing(new DrawingPayload(1, null), null, LIMITS), "strokes",
                "required");
        assertRefused(() -> DoodleValidator.drawing(new DrawingPayload(2, List.of(stroke(0, 2, 1, 1))), null, LIMITS),
                "v", "unsupported");
        assertRefused(() -> drawing(stroke(12, 2, 1, 1)), "c", "not_in_palette");
        assertRefused(() -> drawing(stroke(-1, 2, 1, 1)), "c", "not_in_palette");
        assertRefused(() -> drawing(stroke(0, 1, 1, 1)), "w", "not_allowed");
        assertRefused(() -> drawing(stroke(0, 17, 1, 1)), "w", "not_allowed");
        assertRefused(() -> drawing(new StrokeInput(0, 2, new int[] {1, 2, 3})), "p", "pairs");
        assertRefused(() -> drawing(new StrokeInput(0, 2, new int[0])), "p", "pairs");
        assertRefused(() -> drawing(stroke(0, 2, 7680, 1)), "p", "out_of_canvas");
        assertRefused(() -> drawing(stroke(0, 2, 1, -1)), "p", "out_of_canvas");
        assertRefused(() -> DoodleValidator.drawing(new DrawingPayload(1, List.of(stroke(0, 2, 1, 1))),
                new Size(10, 10), LIMITS), "bbox", "not_allowed");
    }

    @Test
    void textKeepsTheClientBoxWithinTheD31Bounds() {
        Validated v = text("안녕\n경희대", 24, 1500, 900, 72, 48);

        assertThat(v.kind()).isEqualTo("TEXT");
        assertThat(v.bbox()).isEqualTo(new Box(1500, 900, 72, 48));
        assertThat(v.footprint()).isEqualTo(Footprint.ofBox(1500, 900, 72, 48, GEOMETRY));
        assertThat(v.chunks()).containsExactly(16 + 3); // (1500, 900) is chunk col 3, row 1
        assertThat(v.payload()).isEqualTo(new TextPayload(1, "안녕\n경희대", 0, 24, 1500, 900));
    }

    @Test
    void textBoxUpperBounds() {
        // Longest line is 3 characters at size 24: up to 72 wide; 2 lines: up to 67.2 high.
        assertThat(text("안녕\n경희대", 24, 0, 0, 72, 67).bbox().h()).isEqualTo(67);
        assertRefused(() -> text("안녕\n경희대", 24, 0, 0, 73, 48), "bbox", "too_big");
        assertRefused(() -> text("안녕\n경희대", 24, 0, 0, 72, 68), "bbox", "too_big");
        assertRefused(() -> text("가", 24, 7670, 0, 20, 24), "bbox", "out_of_range");
        assertRefused(() -> text("가", 24, 0, 0, 0, 24), "bbox", "out_of_range");
        assertRefused(() -> textWith(new TextPayload(1, "가", 0, 24, 0, 0), null), "bbox", "required");
        // Many line breaks cannot draw outside the bbox limit: 11 lines of 96px.
        assertRefused(() -> text("가" + "\n".repeat(10), 96, 0, 0, 96, 1024), "bbox", "too_big");
    }

    @Test
    void textLengthCountsCharactersWithoutLineBreaks() {
        String fifty = "가".repeat(25) + "\n" + "나".repeat(25);
        assertThat(text(fifty, 10, 0, 0, 250, 28).kind()).isEqualTo("TEXT");
        assertRefused(() -> text(fifty + "다", 10, 0, 0, 260, 28), "text", "too_long");
        // An emoji is one character.
        assertThat(text("😀".repeat(50), 10, 0, 0, 500, 14).kind()).isEqualTo("TEXT");
    }

    @Test
    void textsAreRefusedField() {
        assertRefused(() -> text("", 24, 0, 0, 1, 24), "text", "blank");
        assertRefused(() -> text(" \n ", 24, 0, 0, 24, 48), "text", "blank");
        assertRefused(() -> text("a\tb", 24, 0, 0, 72, 24), "text", "control_character");
        assertRefused(() -> text("a\rb", 24, 0, 0, 72, 24), "text", "control_character");
        assertRefused(() -> text("a\u0000", 24, 0, 0, 48, 24), "text", "control_character");
        assertRefused(() -> text("a\uD800", 24, 0, 0, 48, 24), "text", "control_character");
        assertRefused(() -> textWith(new TextPayload(1, null, 0, 24, 0, 0), new Size(1, 1)), "text", "required");
        assertRefused(() -> textWith(new TextPayload(1, "a", 0, 25, 0, 0), new Size(1, 1)), "size", "not_allowed");
        assertRefused(() -> textWith(new TextPayload(1, "a", 12, 24, 0, 0), new Size(1, 1)), "c",
                "not_in_palette");
        assertRefused(() -> textWith(new TextPayload(1, "a", 0, 24, 7680, 0), new Size(1, 1)), "x",
                "out_of_canvas");
        assertRefused(() -> textWith(new TextPayload(1, "a", 0, 24, null, 0), new Size(1, 1)), "x",
                "out_of_canvas");
        assertRefused(() -> textWith(new TextPayload(null, "a", 0, 24, 0, 0), new Size(1, 1)), "v", "unsupported");
    }

    private static StrokeInput stroke(int c, int w, int... points) {
        return new StrokeInput(c, w, points);
    }

    private static Validated drawing(StrokeInput... strokes) {
        return DoodleValidator.drawing(new DrawingPayload(1, Arrays.asList(strokes)), null, LIMITS);
    }

    private static Validated text(String text, int size, int x, int y, int w, int h) {
        return textWith(new TextPayload(1, text, 0, size, x, y), new Size(w, h));
    }

    private static Validated textWith(TextPayload payload, Size size) {
        return DoodleValidator.text(payload, size, LIMITS);
    }

    private static void assertRefused(Runnable call, String field, String reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(InvalidDoodleException.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.reason()).isEqualTo(reason);
                });
    }
}
