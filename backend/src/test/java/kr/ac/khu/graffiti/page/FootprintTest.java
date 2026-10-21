package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FootprintTest {

    // Same shape as the default config: 7680x4320 canvas, 48px cells -> 160x90.
    private static final CellGeometry GRID = new CellGeometry(7680, 4320, 48);

    @Test
    void horizontalSegmentCoversItsWidthPaddedBoundingBox() {
        // x 96..204, y 96..104 -> cols 2..4, row 2
        Footprint f = Footprint.ofStrokes(List.of(new Footprint.Stroke(8, new int[] {100, 100, 200, 100})), GRID);

        assertThat(cells(f)).containsExactly(cell(2, 2), cell(3, 2), cell(4, 2));
    }

    @Test
    void eachSegmentGetsItsOwnBoxSoAnLShapeSkipsTheInnerCorner() {
        // (10,10)->(300,10)->(300,300): the inside of the L is not covered (D-14).
        Footprint f = Footprint.ofStrokes(
                List.of(new Footprint.Stroke(2, new int[] {10, 10, 300, 10, 300, 300})), GRID);

        assertThat(cells(f)).contains(cell(0, 0), cell(6, 0), cell(6, 6)).doesNotContain(cell(2, 3), cell(0, 6));
    }

    @Test
    void diagonalSegmentCoversItsWholeBoundingBox() {
        Footprint f = Footprint.ofStrokes(List.of(new Footprint.Stroke(2, new int[] {0, 0, 100, 100})), GRID);

        assertThat(cells(f)).hasSize(9); // cols 0..2 x rows 0..2
    }

    @Test
    void singlePointStrokeCoversItsWidthBox() {
        Footprint f = Footprint.ofStrokes(List.of(new Footprint.Stroke(16, new int[] {48, 48})), GRID);

        // x 40..56 -> cols 0..1, y 40..56 -> rows 0..1
        assertThat(cells(f)).containsExactly(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1));
    }

    @Test
    void cellsAreUnionedAcrossStrokesOfOneDoodle() {
        Footprint f = Footprint.ofStrokes(List.of(
                new Footprint.Stroke(2, new int[] {10, 10, 20, 10}),
                new Footprint.Stroke(2, new int[] {15, 15, 25, 15})), GRID);

        assertThat(cells(f)).containsExactly(cell(0, 0));
    }

    @Test
    void geometryOutsideTheCanvasIsClipped() {
        Footprint f = Footprint.ofStrokes(List.of(new Footprint.Stroke(16, new int[] {7679, 4319, 7700, 4400})), GRID);

        assertThat(cells(f)).containsExactly(cell(159, 89));
    }

    @Test
    void geometryEntirelyOutsideTheCanvasCoversNothing() {
        Footprint f = Footprint.ofBox(-500, -500, 100, 100, GRID);

        assertThat(f.cells()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 48, 48, 1",
        "47, 47, 2, 2, 4",
        "48, 0, 48, 48, 1",
        "0, 0, 49, 1, 2",
        "7632, 4272, 48, 48, 1",
    })
    void textBoxCoversCellsItOverlaps(int x, int y, int w, int h, int expectedCells) {
        assertThat(Footprint.ofBox(x, y, w, h, GRID).cells()).hasSize(expectedCells);
    }

    @Test
    void rejectsOddPointArrays() {
        assertThatThrownBy(() -> Footprint.ofStrokes(List.of(new Footprint.Stroke(2, new int[] {1, 2, 3})), GRID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellsAreSortedAndUnique() {
        Footprint f = Footprint.ofCells(new int[] {5, 3, 5, 1}, GRID);

        assertThat(f.cells()).containsExactly(1, 3, 5);
    }

    private static int cell(int col, int row) {
        return row * GRID.cols() + col;
    }

    private static List<Integer> cells(Footprint f) {
        return java.util.Arrays.stream(f.cells()).boxed().toList();
    }
}
