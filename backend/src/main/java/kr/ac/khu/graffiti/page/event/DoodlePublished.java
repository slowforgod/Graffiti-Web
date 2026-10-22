package kr.ac.khu.graffiti.page.event;

import java.util.List;

/** Published after a doodle's row is committed; {@code chunks} are the chunk topics it goes to (D-24). */
public record DoodlePublished(long pageId, List<Integer> chunks, long doodleId, String kind, int x, int y, int w,
        int h, String payload) {
}
