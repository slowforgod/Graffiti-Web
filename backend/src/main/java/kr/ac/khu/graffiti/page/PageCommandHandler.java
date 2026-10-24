package kr.ac.khu.graffiti.page;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.event.DoodlePublished;
import kr.ac.khu.graffiti.page.event.DoodleRemoved;
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
    private final PostStore posts;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final GraffitiProperties props;
    private final Clock clock;

    private PageState current;

    PageCommandHandler(PageRepository pages, PostStore posts, TransactionTemplate tx, ApplicationEventPublisher events,
            GraffitiProperties props, Clock clock) {
        this.pages = pages;
        this.posts = posts;
        this.tx = tx;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    PageSnapshot snapshot() {
        return requireCurrent().snapshot();
    }

    Minimap minimap(PageCommand.GetMinimap c) {
        OccupancyGrid grid = requireCurrent(c.pageId()).grid();
        return new Minimap(grid.geometry().cols(), grid.geometry().rows(), grid.bitmap(), grid.occupancyPermille());
    }

    PageSnapshot applyFootprint(PageCommand.ApplyFootprint c) {
        return commitFootprint(requireCurrent(c.pageId()), c.footprint(), c.delta(), null, at -> null).state()
                .snapshot();
    }

    PageCommand.Reservation reservePost(PageCommand.ReservePost c) {
        requireCurrent(c.pageId());
        PostStore.Poster poster = posts.poster(c.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (poster.banned()) {
            throw new ApiException(ErrorCode.BANNED);
        }
        Instant now = now();
        if (poster.admin()) {
            return new PageCommand.Reservation(c.userId(), now, poster.lastPostedAt(), true);
        }
        Instant last = poster.lastPostedAt();
        if (last != null && last.plus(props.postCooldown()).isAfter(now)) {
            throw cooldown(last.plus(props.postCooldown()), now);
        }
        Boolean taken = tx.execute(s -> posts.reserve(c.userId(), last, now));
        if (!Boolean.TRUE.equals(taken)) {
            // Banned (or otherwise changed) between the read and the update.
            throw new ApiException(ErrorCode.BANNED);
        }
        return new PageCommand.Reservation(c.userId(), now, last, false);
    }

    PageCommand.Published publish(PageCommand.PublishDoodle c) {
        PostStore.NewDoodle doodle = c.doodle();
        PageState state = requireCurrent(doodle.pageId());
        Committed<Long> done = commitFootprint(state, c.footprint(), +1, doodle,
                at -> posts.insertPublished(doodle, at));
        return new PageCommand.Published(done.result(), done.at());
    }

    long withhold(PageCommand.WithholdDoodle c) {
        PostStore.NewDoodle doodle = c.doodle();
        requireCurrent(doodle.pageId());
        String action = switch (c.status()) {
            case "REJECTED" -> "REJECT";
            case "HELD" -> "HOLD";
            default -> throw new IllegalArgumentException("not a withheld status: " + c.status());
        };
        Instant now = now();
        return tx.execute(s -> {
            long id = posts.insertWithheld(doodle, c.status());
            posts.recordAction(new PostStore.Action("AI", null, action, id, doodle.authorId(), null,
                    doodle.moderationDetail()), now);
            return id;
        });
    }

    PageCommand.Published publishWithheld(PageCommand.PublishWithheld c) {
        PostStore.NewDoodle doodle = c.doodle();
        PageState state = requireCurrent(doodle.pageId());
        String action = "HELD".equals(c.from()) ? "APPROVE" : "RESTORE";
        Committed<Long> done = commitFootprint(state, c.footprint(), +1, doodle, at -> {
            if (!posts.publishWithheld(c.doodleId(), doodle.pageId(), c.from(), at)) {
                throw new ApiException(ErrorCode.STATE_CONFLICT);
            }
            posts.recordAction(new PostStore.Action("ADMIN", c.adminId(), action, c.doodleId(), doodle.authorId(),
                    null, null), at);
            return c.doodleId();
        });
        return new PageCommand.Published(done.result(), done.at());
    }

    boolean rejectHeld(PageCommand.RejectHeld c) {
        Instant now = now();
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!posts.rejectHeld(c.doodleId())) {
                return false;
            }
            posts.recordAction(new PostStore.Action("ADMIN", c.adminId(), "REJECT", c.doodleId(), c.authorId(),
                    null, null), now);
            return true;
        }));
    }

    List<Long> removeDoodles(PageCommand.RemoveDoodles c) {
        PageState state = requireCurrent(c.pageId());
        OccupancyGrid grid = state.grid().copy();
        List<Integer> clear = new ArrayList<>();
        Instant now = now();
        tx.execute(s -> {
            for (PageCommand.Removal r : c.removals()) {
                if (!posts.deleteOwn(r.doodleId(), c.pageId(), c.authorId(), now)) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT);
                }
                // Only rows that were still PUBLISHED reach here, so their cells are in the grid.
                clear.addAll(grid.apply(r.footprint(), -1).clear());
            }
            return null;
        });
        // A countdown never stops once started (INV-03), so removing cells only changes the grid.
        current = state.withGrid(grid);
        c.removals().forEach(r -> events.publishEvent(new DoodleRemoved(c.pageId(), r.doodleId(), r.chunks())));
        if (!clear.isEmpty()) {
            events.publishEvent(new OccupancyChanged(c.pageId(), List.of(), List.copyOf(clear),
                    grid.occupancyPermille()));
        }
        return c.removals().stream().map(PageCommand.Removal::doodleId).toList();
    }

    boolean release(PageCommand.ReleasePost c) {
        PageCommand.Reservation r = c.reservation();
        if (r.exempt()) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(s -> posts.restore(r.userId(), r.reservedAt(), r.previous())));
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

    private record Committed<T>(PageState state, Instant at, T result) {
    }

    /**
     * Adds or removes one footprint. {@code writes} run in the same transaction as a countdown start;
     * {@code published}, when given, is announced before the cell changes. Memory and events change only
     * after the commit.
     */
    private <T> Committed<T> commitFootprint(PageState state, Footprint footprint, int delta,
            PostStore.NewDoodle published, Function<Instant, T> writes) {
        OccupancyGrid grid = state.grid().copy();
        OccupancyGrid.CellChanges changes = grid.apply(footprint, delta);
        Instant now = now();
        boolean countdown = PageLifecycle.shouldStartCountdown(state.status(), grid.occupancyPermille(),
                state.rules());
        Instant endAt = countdown ? PageLifecycle.scheduledEnd(state.createdAt(), now, state.rules()) : null;

        T result = null;
        if (countdown || published != null) {
            result = tx.execute(s -> {
                T written = writes.apply(now);
                if (countdown) {
                    requireOneRow(pages.startCountdown(state.id(), now, endAt), state);
                }
                return written;
            });
        }
        // Occupancy itself is not stored: it is rebuilt from published doodles (INV-12).
        PageState next = countdown ? state.withCountdown(now, endAt, grid) : state.withGrid(grid);

        current = next;
        if (published != null) {
            events.publishEvent(new DoodlePublished(next.id(), published.chunks(), (Long) result,
                    published.kind(), published.x(), published.y(), published.w(), published.h(),
                    published.payload()));
        }
        if (!changes.isEmpty()) {
            events.publishEvent(new OccupancyChanged(next.id(), changes.set(), changes.clear(),
                    grid.occupancyPermille()));
        }
        if (next.status() != state.status()) {
            publishStatus(next);
        }
        return new Committed<>(next, now, result);
    }

    private static ApiException cooldown(Instant endsAt, Instant now) {
        long seconds = (Duration.between(now, endsAt).toMillis() + 999) / 1000;
        long minutes = (seconds + 59) / 60;
        return new ApiException(ErrorCode.COOLDOWN, "다음 게시까지 " + minutes + "분 남았어요.",
                Map.of("retryAfterSec", seconds));
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
