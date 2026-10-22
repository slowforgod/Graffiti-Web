package kr.ac.khu.graffiti.realtime;

/**
 * Body of {@code /app/draft} (API.md §6). Which fields an op may carry is checked by
 * {@link DraftRules}; any field not listed here fails parsing (SEC-02). There is no {@code text}
 * field: a text draft's letters never leave the author's screen (D-33).
 */
record DraftMessage(
        String pageId,
        String draftId,
        String op,
        Integer strokeIndex,
        Integer c,
        Integer w,
        int[] points,
        Box bbox) {

    record Box(Integer x, Integer y, Integer w, Integer h) {
    }
}
