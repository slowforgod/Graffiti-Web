package kr.ac.khu.graffiti.page;

/**
 * Work for the page command queue (INV-01). Each command names the page it targets; a command for a
 * page that is not current fails with {@code PAGE_ENDED} (INV-04).
 *
 * @param <R> result type
 */
public sealed interface PageCommand<R> {

    /** Dispatches to the handler; runs on the queue thread only. */
    R executeOn(PageCommandHandler handler);

    /** Adds ({@code delta = +1}) or removes ({@code -1}) one doodle's cells. May start the countdown. */
    record ApplyFootprint(long pageId, Footprint footprint, int delta) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.applyFootprint(this);
        }
    }

    /** Ends the page when its scheduled end has passed and opens the next one. */
    record EndPage(long pageId) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.endPage(this);
        }
    }

    /** Reads the current page. */
    record GetPageSnapshot() implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.snapshot();
        }
    }

    /** Reads the occupancy bitmap of {@code pageId}, which must be the current page (D-08). */
    record GetMinimap(long pageId) implements PageCommand<Minimap> {

        @Override
        public Minimap executeOn(PageCommandHandler handler) {
            return handler.minimap(this);
        }
    }

    /** Startup only: returns the live page id, creating page seq 1 (or the next seq) when none exists. */
    record EnsureLivePage() implements PageCommand<Long> {

        @Override
        public Long executeOn(PageCommandHandler handler) {
            return handler.ensureLivePage();
        }
    }

    /** Startup only: makes {@code pageId} current with a grid rebuilt outside the queue (INV-02, D-25). */
    record InstallPage(long pageId, OccupancyGrid grid) implements PageCommand<PageSnapshot> {

        @Override
        public PageSnapshot executeOn(PageCommandHandler handler) {
            return handler.install(this);
        }
    }
}
