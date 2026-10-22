package kr.ac.khu.graffiti.page;

/** Copy of the current page's occupancy bitmap ({@link OccupancyGrid#bitmap()} layout) for the minimap (D-08). */
public record Minimap(int cols, int rows, byte[] bitmap, int occupancyPermille) {
}
