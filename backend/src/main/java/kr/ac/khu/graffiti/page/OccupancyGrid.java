package kr.ac.khu.graffiti.page;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-cell reference counts of published doodles (D-14, INV-12). A cell is occupied while its count
 * is above zero. Not thread-safe: only the page command queue mutates it (INV-01).
 */
public final class OccupancyGrid {

    /** Cells that went from 0 to 1 ({@code set}) and from 1 to 0 ({@code clear}). */
    public record CellChanges(List<Integer> set, List<Integer> clear) {

        public boolean isEmpty() {
            return set.isEmpty() && clear.isEmpty();
        }
    }

    private final CellGeometry geometry;
    private final int[] counts;
    private int occupied;

    public OccupancyGrid(CellGeometry geometry) {
        this.geometry = geometry;
        this.counts = new int[geometry.cellCount()];
    }

    private OccupancyGrid(OccupancyGrid source) {
        this.geometry = source.geometry;
        this.counts = source.counts.clone();
        this.occupied = source.occupied;
    }

    public OccupancyGrid copy() {
        return new OccupancyGrid(this);
    }

    /** Adds ({@code +1}) or removes ({@code -1}) one doodle. Either applies fully or throws without change. */
    public CellChanges apply(Footprint footprint, int delta) {
        if (delta != 1 && delta != -1) {
            throw new IllegalArgumentException("delta must be +1 or -1");
        }
        int[] cells = footprint.cellsUnsafe();
        if (delta < 0) {
            for (int cell : cells) {
                if (counts[cell] == 0) {
                    throw new IllegalStateException("removing a footprint that was never added, cell " + cell);
                }
            }
        }
        List<Integer> set = new ArrayList<>();
        List<Integer> clear = new ArrayList<>();
        for (int cell : cells) {
            counts[cell] += delta;
            if (delta > 0 && counts[cell] == 1) {
                set.add(cell);
            } else if (delta < 0 && counts[cell] == 0) {
                clear.add(cell);
            }
        }
        occupied += set.size() - clear.size();
        return new CellChanges(List.copyOf(set), List.copyOf(clear));
    }

    public CellGeometry geometry() {
        return geometry;
    }

    public int occupiedCells() {
        return occupied;
    }

    /** Occupied share in permille, rounded down. */
    public int occupancyPermille() {
        return (int) ((long) occupied * 1000 / counts.length);
    }

    /** One bit per cell, row-major ({@code row * cols + col}), most significant bit first in each byte (D-08). */
    public byte[] bitmap() {
        byte[] bits = new byte[(counts.length + 7) / 8];
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                bits[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }
        return bits;
    }
}
