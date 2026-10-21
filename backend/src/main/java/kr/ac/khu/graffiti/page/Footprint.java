package kr.ac.khu.graffiti.page;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * The occupancy cells one doodle covers (D-14). A drawing covers, per segment between two
 * neighbouring points, the segment's bounding box widened by half the stroke width; a text covers
 * its box. Cells are counted once per doodle. Geometry outside the canvas is clipped.
 */
public final class Footprint {

    /** One stroke: width in px and points as {@code [x0, y0, x1, y1, ...]} in world coordinates. */
    public record Stroke(int width, int[] points) {
    }

    private final int[] cells;

    private Footprint(int[] cells) {
        this.cells = cells;
    }

    public static Footprint ofStrokes(List<Stroke> strokes, CellGeometry geometry) {
        BitSet covered = new BitSet(geometry.cellCount());
        for (Stroke stroke : strokes) {
            int[] p = stroke.points();
            if (p.length == 0 || p.length % 2 != 0) {
                throw new IllegalArgumentException("stroke points must be non-empty x,y pairs");
            }
            double half = stroke.width() / 2.0;
            if (p.length == 2) {
                cover(covered, p[0] - half, p[1] - half, p[0] + half, p[1] + half, geometry);
                continue;
            }
            for (int i = 0; i + 3 < p.length; i += 2) {
                cover(covered,
                        Math.min(p[i], p[i + 2]) - half, Math.min(p[i + 1], p[i + 3]) - half,
                        Math.max(p[i], p[i + 2]) + half, Math.max(p[i + 1], p[i + 3]) + half,
                        geometry);
            }
        }
        return new Footprint(covered.stream().toArray());
    }

    /** A text box with its top-left corner at (x, y). */
    public static Footprint ofBox(int x, int y, int width, int height, CellGeometry geometry) {
        BitSet covered = new BitSet(geometry.cellCount());
        cover(covered, x, y, (double) x + width, (double) y + height, geometry);
        return new Footprint(covered.stream().toArray());
    }

    /** Cell indices ({@code row * cols + col}) directly; used when rebuilding from stored data and in tests. */
    public static Footprint ofCells(int[] cellIndices, CellGeometry geometry) {
        BitSet covered = new BitSet(geometry.cellCount());
        for (int cell : cellIndices) {
            if (cell < 0 || cell >= geometry.cellCount()) {
                throw new IllegalArgumentException("cell index out of range: " + cell);
            }
            covered.set(cell);
        }
        return new Footprint(covered.stream().toArray());
    }

    /** Sorted, unique cell indices. */
    public int[] cells() {
        return cells.clone();
    }

    int[] cellsUnsafe() {
        return cells;
    }

    // Covers the half-open pixel rectangle [minX, maxX) x [minY, maxY), clipped to the canvas.
    private static void cover(BitSet covered, double minX, double minY, double maxX, double maxY,
            CellGeometry g) {
        double x0 = Math.max(minX, 0);
        double y0 = Math.max(minY, 0);
        double x1 = Math.min(maxX, g.width());
        double y1 = Math.min(maxY, g.height());
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        int colMin = (int) Math.floor(x0 / g.cellSize());
        int colMax = (int) Math.ceil(x1 / g.cellSize()) - 1;
        int rowMin = (int) Math.floor(y0 / g.cellSize());
        int rowMax = (int) Math.ceil(y1 / g.cellSize()) - 1;
        for (int row = rowMin; row <= rowMax; row++) {
            covered.set(row * g.cols() + colMin, row * g.cols() + colMax + 1);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Footprint other && Arrays.equals(cells, other.cells);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cells);
    }
}
