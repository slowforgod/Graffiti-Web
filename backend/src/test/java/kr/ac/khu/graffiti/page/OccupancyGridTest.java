package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OccupancyGridTest {

    private static final CellGeometry GRID = new CellGeometry(7680, 4320, 48);

    @Test
    void addingReportsNewlyOccupiedCells() {
        OccupancyGrid grid = new OccupancyGrid(GRID);

        OccupancyGrid.CellChanges changes = grid.apply(Footprint.ofCells(new int[] {1, 2}, GRID), +1);

        assertThat(changes.set()).containsExactly(1, 2);
        assertThat(changes.clear()).isEmpty();
        assertThat(grid.occupiedCells()).isEqualTo(2);
    }

    @Test
    void sharedCellStaysOccupiedUntilLastReferenceIsRemoved() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        Footprint a = Footprint.ofCells(new int[] {7, 8}, GRID);
        Footprint b = Footprint.ofCells(new int[] {8, 9}, GRID);
        grid.apply(a, +1);

        assertThat(grid.apply(b, +1).set()).containsExactly(9);
        OccupancyGrid.CellChanges removeA = grid.apply(a, -1);

        assertThat(removeA.clear()).containsExactly(7);
        assertThat(grid.occupiedCells()).isEqualTo(2);
    }

    @Test
    void addThenRemoveRestoresTheGrid() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        Footprint f = Footprint.ofBox(100, 100, 500, 300, GRID);
        byte[] before = grid.bitmap();

        grid.apply(f, +1);
        grid.apply(f, -1);

        assertThat(grid.bitmap()).isEqualTo(before);
        assertThat(grid.occupiedCells()).isZero();
    }

    @Test
    void resultDoesNotDependOnApplicationOrder() {
        Random random = new Random(42);
        List<Footprint> footprints = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            footprints.add(Footprint.ofBox(random.nextInt(7000), random.nextInt(4000), 1 + random.nextInt(600),
                    1 + random.nextInt(300), GRID));
        }
        OccupancyGrid inOrder = new OccupancyGrid(GRID);
        footprints.forEach(f -> inOrder.apply(f, +1));

        Collections.shuffle(footprints, random);
        OccupancyGrid shuffled = new OccupancyGrid(GRID);
        footprints.forEach(f -> shuffled.apply(f, +1));

        assertThat(shuffled.bitmap()).isEqualTo(inOrder.bitmap());
        assertThat(shuffled.occupiedCells()).isEqualTo(inOrder.occupiedCells());
    }

    @Test
    void removingAnAbsentFootprintFailsWithoutChangingAnything() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        grid.apply(Footprint.ofCells(new int[] {1}, GRID), +1);

        assertThatThrownBy(() -> grid.apply(Footprint.ofCells(new int[] {1, 2}, GRID), -1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(grid.occupiedCells()).isEqualTo(1);
    }

    @Test
    void rejectsDeltaOtherThanPlusOrMinusOne() {
        OccupancyGrid grid = new OccupancyGrid(GRID);

        assertThatThrownBy(() -> grid.apply(Footprint.ofCells(new int[] {1}, GRID), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permilleRoundsDown() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        // 10,079 of 14,400 cells = 699.93 permille -> 699
        grid.apply(Footprint.ofCells(IntStream.range(0, 10_079).toArray(), GRID), +1);
        assertThat(grid.occupancyPermille()).isEqualTo(699);

        grid.apply(Footprint.ofCells(new int[] {10_079}, GRID), +1);
        assertThat(grid.occupancyPermille()).isEqualTo(700);
    }

    @Test
    void bitmapIsRowMajorOneBitPerCellMostSignificantBitFirst() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        int col = 3;
        int row = 1;
        grid.apply(Footprint.ofCells(new int[] {0, row * 160 + col}, GRID), +1);

        byte[] bitmap = grid.bitmap();

        assertThat(bitmap).hasSize(1800);
        assertThat(bitmap[0] & 0xFF).isEqualTo(0b1000_0000);
        int index = row * 160 + col; // 163 -> byte 20, bit 3 from the left
        assertThat(bitmap[index / 8] & 0xFF).isEqualTo(0x80 >>> (index % 8));
    }

    @Test
    void copyIsIndependent() {
        OccupancyGrid grid = new OccupancyGrid(GRID);
        OccupancyGrid copy = grid.copy();

        copy.apply(Footprint.ofCells(new int[] {1}, GRID), +1);

        assertThat(grid.occupiedCells()).isZero();
        assertThat(copy.occupiedCells()).isEqualTo(1);
    }
}
