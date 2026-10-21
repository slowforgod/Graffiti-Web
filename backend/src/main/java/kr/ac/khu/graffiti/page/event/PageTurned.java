package kr.ac.khu.graffiti.page.event;

/** Published after a page ended and the next one was created (D-16). */
public record PageTurned(long oldPageId, long newPageId) {
}
