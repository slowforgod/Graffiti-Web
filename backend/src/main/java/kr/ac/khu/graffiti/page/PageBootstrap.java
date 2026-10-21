package kr.ac.khu.graffiti.page;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (D-25, ARCHITECTURE.md §4.5): find or create the live page, rebuild its occupancy
 * from published doodles outside the queue, then end it at once if its time has passed. Runs in a
 * lifecycle phase before the web server starts, so no request sees a page without state.
 */
@Component
class PageBootstrap implements SmartLifecycle {

    /** Earlier than the embedded web server (which starts near {@link SmartLifecycle#DEFAULT_PHASE}). */
    static final int PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(PageBootstrap.class);

    private final PageCommandProcessor processor;
    private final PageRepository pages;
    private final PublishedFootprintSource footprints;
    private final Clock clock;

    private volatile boolean running;

    PageBootstrap(PageCommandProcessor processor, PageRepository pages, PublishedFootprintSource footprints,
            Clock clock) {
        this.processor = processor;
        this.pages = pages;
        this.footprints = footprints;
        this.clock = clock;
    }

    @Override
    public void start() {
        recover();
        running = true;
    }

    PageSnapshot recover() {
        long pageId = processor.submit(new PageCommand.EnsureLivePage()).join();
        Page page = pages.findById(pageId).orElseThrow();

        OccupancyGrid grid = new OccupancyGrid(page.geometry());
        footprints.load(pageId, page.geometry()).forEach(f -> grid.apply(f, +1));

        PageSnapshot snapshot = processor.submit(new PageCommand.InstallPage(pageId, grid)).join();
        log.info("Page {} (seq {}) recovered: {}, {} permille", snapshot.id(), snapshot.seq(), snapshot.status(),
                snapshot.occupancyPermille());
        if (!clock.instant().isBefore(snapshot.scheduledEndAt())) {
            snapshot = processor.submit(new PageCommand.EndPage(pageId)).join();
        }
        return snapshot;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
