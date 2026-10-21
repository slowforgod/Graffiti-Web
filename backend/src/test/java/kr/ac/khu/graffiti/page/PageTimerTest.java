package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

class PageTimerTest {

    private static final Instant END = Instant.parse("2026-10-08T00:00:00Z");

    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<PageCommand<?>> submitted = new ArrayList<>();
    private final PageTimer timer = new PageTimer(scheduler, new PageCommandProcessor() {
        @Override
        public <R> CompletableFuture<R> submit(PageCommand<R> command) {
            submitted.add(command);
            return new CompletableFuture<>();
        }
    });

    @Test
    void schedulesEndPageAtScheduledEnd() {
        timer.onStatusChanged(status(7, END));

        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.tasks.getFirst().at).isEqualTo(END);
        scheduler.tasks.getFirst().runnable.run();
        assertThat(submitted).containsExactly(new PageCommand.EndPage(7));
    }

    @Test
    void earlierEndFromCountdownReplacesThePreviousSchedule() {
        timer.onStatusChanged(status(7, END));
        Instant earlier = END.minus(Duration.ofDays(3));

        timer.onStatusChanged(status(7, earlier));

        assertThat(scheduler.tasks).hasSize(2);
        assertThat(scheduler.tasks.get(0).future.isCancelled()).isTrue();
        assertThat(scheduler.tasks.get(1).at).isEqualTo(earlier);
        assertThat(scheduler.tasks.get(1).future.isCancelled()).isFalse();
    }

    @Test
    void newPageReplacesTheOldPagesSchedule() {
        timer.onStatusChanged(status(7, END));

        timer.onStatusChanged(status(8, END.plus(Duration.ofDays(7))));
        scheduler.tasks.get(1).runnable.run();

        assertThat(scheduler.tasks.get(0).future.isCancelled()).isTrue();
        assertThat(submitted).containsExactly(new PageCommand.EndPage(8));
    }

    private static PageStatusChanged status(long pageId, Instant endAt) {
        return new PageStatusChanged(pageId, PageStatus.ACTIVE, 0, null, endAt);
    }

    private static final class FakeScheduler implements TaskScheduler {

        record Task(Runnable runnable, Instant at, FakeFuture future) {
        }

        final List<Task> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            FakeFuture future = new FakeFuture();
            tasks.add(new Task(task, startTime, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeFuture extends CompletableFuture<Object> implements ScheduledFuture<Object> {

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
