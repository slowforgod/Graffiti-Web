package kr.ac.khu.graffiti.page;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.event.OccupancyChanged;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import kr.ac.khu.graffiti.page.event.PageTurned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes page commands. Called only from the queue thread ({@link SingleThreadPageCommandProcessor}).
 * Each command computes its new state on a copy, commits the DB change, and only then swaps the
 * in-memory state and publishes events, so a failed write leaves memory untouched.
 */
@Component
class PageCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PageCommandHandler.class);

    private final PageRepository pages;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final GraffitiProperties props;
    private final Clock clock;

    private PageState current;

    PageCommandHandler(PageRepository pages, TransactionTemplate tx, ApplicationEventPublisher events,
            GraffitiProperties props, Clock clock) {
        this.pages = pages;
        this.tx = tx;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    PageSnapshot snapshot() {
        return requireCurrent().snapshot();
    }

    PageSnapshot applyFootprint(PageCommand.ApplyFootprint c) {
        PageState state = requireCurrent(c.pageId());
        OccupancyGrid grid = state.grid().copy();
        OccupancyGrid.CellChanges changes = grid.apply(c.footprint(), c.delta());

        PageState next;
        if (PageLifecycle.shouldStartCountdown(state.status(), grid.occupancyPermille(), state.rules())) {
            Instant now = now();
            Instant endAt = PageLifecycle.scheduledEnd(state.createdAt(), now, state.rules());
            tx.executeWithoutResult(s -> requireOneRow(pages.startCountdown(state.id(), now, endAt), state));
            next = state.withCountdown(now, endAt, grid);
        } else {
            // Occupancy itself is not stored: it is rebuilt from published doodles (INV-12).
            next = state.withGrid(grid);
        }

        current = next;
        if (!changes.isEmpty()) {
            events.publishEvent(new OccupancyChanged(next.id(), changes.set(), changes.clear(),
                    grid.occupancyPermille()));
        }
        if (next.status() != state.status()) {
            publishStatus(next);
        }
        return next.snapshot();
    }

    PageSnapshot endPage(PageCommand.EndPage c) {
        PageState state = requireCurrent(c.pageId());
        Instant now = now();
        if (now.isBefore(state.scheduledEndAt())) {
            // A timer that fired early: keep the page and let the listener schedule it again.
            publishStatus(state);
            return state.snapshot();
        }

        PageEndReason reason = PageLifecycle.endReason(state.createdAt(), state.countdownStartedAt(), state.rules());
        Page created = tx.execute(s -> {
            requireOneRow(pages.end(state.id(), state.status(), now, reason, state.grid().occupancyPermille(),
                    state.grid().bitmap()), state);
            return pages.save(newPageFromConfig(state.seq() + 1, now));
        });

        PageState next = PageState.of(created, new OccupancyGrid(created.geometry()));
        current = next;
        log.info("Page {} ended ({}), page {} started", state.id(), reason, next.id());
        events.publishEvent(new PageTurned(state.id(), next.id()));
        publishStatus(next);
        return next.snapshot();
    }

    long ensureLivePage() {
        return tx.execute(s -> pages.findLive()
                .orElseGet(() -> pages.save(newPageFromConfig(pages.findMaxSeq() + 1, now())))
                .getId());
    }

    PageSnapshot install(PageCommand.InstallPage c) {
        Page page = pages.findById(c.pageId())
                .filter(p -> p.getStatus() != PageStatus.ENDED)
                .orElseThrow(() -> new IllegalStateException("page " + c.pageId() + " is not live"));
        current = PageState.of(page, c.grid());
        publishStatus(current);
        return current.snapshot();
    }

    private Page newPageFromConfig(int seq, Instant now) {
        GraffitiProperties.Canvas canvas = props.canvas();
        GraffitiProperties.Page page = props.page();
        PageRules rules = new PageRules(PageRules.thresholdToPermille(page.countdownThreshold()),
                page.countdownDuration(), page.maxLifetime());
        return Page.newActive(seq, new CellGeometry(canvas.width(), canvas.height(), canvas.cellSize()),
                canvas.chunkSize(), rules, now);
    }

    // The database keeps milliseconds (DATETIME(3)); match it so memory and rows agree after a restart.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void publishStatus(PageState state) {
        events.publishEvent(new PageStatusChanged(state.id(), state.status(), state.grid().occupancyPermille(),
                state.countdownStartedAt(), state.scheduledEndAt()));
    }

    private PageState requireCurrent() {
        if (current == null) {
            throw new IllegalStateException("no current page yet");
        }
        return current;
    }

    private PageState requireCurrent(long pageId) {
        PageState state = requireCurrent();
        if (state.id() != pageId) {
            throw new ApiException(ErrorCode.PAGE_ENDED);
        }
        return state;
    }

    private static void requireOneRow(int updated, PageState state) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "page " + state.id() + " was not " + state.status() + " in the database");
        }
    }
}
