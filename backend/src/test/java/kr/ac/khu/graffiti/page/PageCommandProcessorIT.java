package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.event.OccupancyChanged;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import kr.ac.khu.graffiti.page.event.PageTurned;
import kr.ac.khu.graffiti.support.IntegrationTest;
import kr.ac.khu.graffiti.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@Import(PageCommandProcessorIT.EventLog.class)
class PageCommandProcessorIT extends IntegrationTest {

    @Autowired
    private PageCommandProcessor processor;

    @MockitoSpyBean
    private PageRepository pages;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GraffitiProperties props;

    @Autowired
    private EventLog events;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    private PageSnapshot page;

    @BeforeEach
    void startOnFreshPage() {
        // Every test starts on a new, empty ACTIVE page created "now".
        page = FreshPage.start(context, clock);
        events.clear();
    }

    @Test
    void addingAFootprintUpdatesOccupancyAndPublishesTheChangedCells() {
        PageSnapshot after = run(apply(cells(0, 3), +1));

        assertThat(after.occupancyPermille()).isZero(); // 3 of 14,400 cells
        assertThat(events.of(OccupancyChanged.class))
                .containsExactly(new OccupancyChanged(page.id(), List.of(0, 1, 2), List.of(), 0));
    }

    @Test
    void reachingTheThresholdStartsTheCountdownOnce() {
        PageSnapshot after = run(apply(cells(0, thresholdCells()), +1));

        assertThat(after.status()).isEqualTo(PageStatus.COUNTDOWN);
        assertThat(after.countdownStartedAt()).isEqualTo(clock.instant());
        assertThat(after.scheduledEndAt()).isEqualTo(clock.instant().plus(props.page().countdownDuration()));
        Page row = pages.findById(page.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PageStatus.COUNTDOWN);
        assertThat(row.getCountdownStartedAt()).isEqualTo(after.countdownStartedAt());
        assertThat(events.of(PageStatusChanged.class)).hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        PageSnapshot more = run(apply(cells(thresholdCells(), 10), +1));
        assertThat(more.countdownStartedAt()).isEqualTo(after.countdownStartedAt());
        assertThat(events.of(PageStatusChanged.class)).hasSize(1);
    }

    @Test
    void droppingBelowTheThresholdDoesNotCancelTheCountdown() {
        Footprint big = cells(0, thresholdCells());
        run(apply(big, +1));

        PageSnapshot after = run(apply(big, -1));

        assertThat(after.occupancyPermille()).isZero();
        assertThat(after.status()).isEqualTo(PageStatus.COUNTDOWN);
        assertThat(pages.findById(page.id()).orElseThrow().getStatus()).isEqualTo(PageStatus.COUNTDOWN);
    }

    @Test
    void countdownEndTurnsThePageWithOccupancyReasonAndFinalBitmap() {
        PageSnapshot countdown = run(apply(cells(0, thresholdCells()), +1));
        clock.set(countdown.scheduledEndAt());

        PageSnapshot next = run(new PageCommand.EndPage(page.id()));

        Page ended = pages.findById(page.id()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(PageStatus.ENDED);
        assertThat(ended.getEndReason()).isEqualTo(PageEndReason.OCCUPANCY);
        assertThat(ended.getEndedAt()).isEqualTo(clock.instant());
        assertThat(ended.getFinalOccupancyPermille()).isEqualTo(thresholdCells() * 1000 / page.geometry().cellCount());
        assertThat(ended.getFinalOccupancyBitmap()).hasSize(1800);
        assertThat(ended.getFinalOccupancyBitmap()[0] & 0xFF).isEqualTo(0xFF);

        assertThat(next.seq()).isEqualTo(page.seq() + 1);
        assertThat(next.status()).isEqualTo(PageStatus.ACTIVE);
        assertThat(next.occupancyPermille()).isZero();
        assertThat(next.scheduledEndAt()).isEqualTo(clock.instant().plus(props.page().maxLifetime()));
        assertThat(events.of(PageTurned.class)).containsExactly(new PageTurned(page.id(), next.id()));
    }

    @Test
    void emptyPageEndsAtMaxLifetime() {
        clock.set(page.scheduledEndAt());

        run(new PageCommand.EndPage(page.id()));

        assertThat(pages.findById(page.id()).orElseThrow().getEndReason()).isEqualTo(PageEndReason.MAX_LIFETIME);
    }

    @Test
    void endPageBeforeTheScheduledEndKeepsThePage() {
        clock.set(page.scheduledEndAt().minusSeconds(1));

        PageSnapshot after = run(new PageCommand.EndPage(page.id()));

        assertThat(after.id()).isEqualTo(page.id());
        assertThat(after.status()).isEqualTo(PageStatus.ACTIVE);
        assertThat(events.of(PageTurned.class)).isEmpty();
    }

    @Test
    void commandsForAnEndedPageAreRejected() {
        clock.set(page.scheduledEndAt());
        run(new PageCommand.EndPage(page.id()));

        assertPageEnded(() -> run(apply(cells(0, 1), +1)));
        assertPageEnded(() -> run(new PageCommand.EndPage(page.id())));
    }

    @Test
    void failedDatabaseWriteLeavesMemoryAndEventsUntouched() {
        run(apply(cells(0, 1), +1));
        events.clear();
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(pages).startCountdown(anyLong(), any(), any());

        assertThatThrownBy(() -> run(apply(cells(1, thresholdCells()), +1)))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);

        PageSnapshot after = run(new PageCommand.GetPageSnapshot());
        assertThat(after.status()).isEqualTo(PageStatus.ACTIVE);
        assertThat(after.occupancyPermille()).isZero();
        assertThat(events.all()).isEmpty();
        // The queue keeps working.
        assertThat(run(apply(cells(5, 1), +1)).id()).isEqualTo(page.id());
    }

    @Test
    void concurrentSubmissionsAreAppliedOneByOne() {
        List<CompletableFuture<PageSnapshot>> futures = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> run(apply(cells(i * 2, 2), +1))))
                .toList();
        futures.forEach(CompletableFuture::join);

        assertThat(events.of(OccupancyChanged.class)).hasSize(50);
        assertThat(events.of(OccupancyChanged.class).stream().mapToInt(e -> e.set().size()).sum()).isEqualTo(100);
    }

    @Test
    void databaseAllowsOnlyOneLivePage() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> pages.saveAndFlush(
                Page.newActive(page.seq() + 100, page.geometry(), page.chunkSize(),
                        new PageRules(700, Duration.ofHours(6), Duration.ofDays(7)), clock.instant()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void conditionalTransitionSucceedsOnceAndQueueRejectsAStaleState() {
        Integer first = tx.execute(s -> pages.startCountdown(page.id(), clock.instant(), page.scheduledEndAt()));
        Integer second = tx.execute(s -> pages.startCountdown(page.id(), clock.instant(), page.scheduledEndAt()));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();

        // Memory still says ACTIVE, the row says COUNTDOWN: the queue refuses instead of overwriting.
        assertThatThrownBy(() -> run(apply(cells(0, thresholdCells()), +1)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(run(new PageCommand.GetPageSnapshot()).occupancyPermille()).isZero();

        // Test cleanup only: put the row back so the next test can end this page normally.
        jdbc.update("UPDATE page SET status = 'ACTIVE', countdown_started_at = NULL WHERE id = ?", page.id());
    }

    private int thresholdCells() {
        int total = page.geometry().cellCount();
        return (int) Math.ceil(total * props.page().countdownThreshold());
    }

    private Footprint cells(int from, int count) {
        return Footprint.ofCells(IntStream.range(from, from + count).toArray(), page.geometry());
    }

    private PageCommand.ApplyFootprint apply(Footprint footprint, int delta) {
        return new PageCommand.ApplyFootprint(page.id(), footprint, delta);
    }

    private <R> R run(PageCommand<R> command) {
        return processor.submit(command).join();
    }

    private static void assertPageEnded(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PAGE_ENDED));
    }

    @TestComponent
    static class EventLog {

        private final List<Object> received = new CopyOnWriteArrayList<>();

        @EventListener({PageStatusChanged.class, OccupancyChanged.class, PageTurned.class})
        void on(Object event) {
            received.add(event);
        }

        void clear() {
            received.clear();
        }

        List<Object> all() {
            return List.copyOf(received);
        }

        <T> List<T> of(Class<T> type) {
            return received.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
