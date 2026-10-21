package kr.ac.khu.graffiti.page;

/** Canvas size and occupancy cell size of one page (D-06). Sizes come from the page row snapshot. */
public record CellGeometry(int width, int height, int cellSize) {

    public CellGeometry {
        if (width <= 0 || height <= 0 || cellSize <= 0 || width % cellSize != 0 || height % cellSize != 0) {
            throw new IllegalArgumentException("canvas must be a positive multiple of the cell size");
        }
    }

    public int cols() {
        return width / cellSize;
    }

    public int rows() {
        return height / cellSize;
    }

    public int cellCount() {
        return cols() * rows();
    }
}
