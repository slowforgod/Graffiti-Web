package kr.ac.khu.graffiti.page.event;

import java.util.List;

/** Published after a doodle left the canvas (deleted or hidden) and its row is committed; {@code chunks} as stored. */
public record DoodleRemoved(long pageId, long doodleId, List<Integer> chunks) {
}
