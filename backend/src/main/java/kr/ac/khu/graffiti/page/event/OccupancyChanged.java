package kr.ac.khu.graffiti.page.event;

import java.util.List;

/** Published after a footprint change flips cells; feeds the minimap diff (D-08). */
public record OccupancyChanged(long pageId, List<Integer> set, List<Integer> clear, int occupancyPermille) {
}
