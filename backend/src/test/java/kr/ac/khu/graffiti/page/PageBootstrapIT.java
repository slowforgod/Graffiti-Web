package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.support.IntegrationTest;
import kr.ac.khu.graffiti.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PageBootstrapIT extends IntegrationTest {

    @Autowired
    private PageBootstrap bootstrap;

    @Autowired
    private PageCommandProcessor processor;

    @Autowired
    private PageRepository pages;

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private PublishedFootprintSource footprints;

    @Test
    void runsBeforeTheWebServerAndInstallsALivePage() {
        assertThat(bootstrap.isRunning()).isTrue();
        // The embedded web server starts at SmartLifecycle.DEFAULT_PHASE - 1024; recovery must come first.
        assertThat(bootstrap.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE - 1024);

        PageSnapshot snapshot = processor.submit(new PageCommand.GetPageSnapshot()).join();
        assertThat(snapshot.status()).isNotEqualTo(PageStatus.ENDED);
        assertThat(pages.findLive()).map(Page::getId).contains(snapshot.id());
    }

    @Test
    void restartRebuildsOccupancyFromPublishedFootprints() {
        PageSnapshot before = processor.submit(new PageCommand.GetPageSnapshot()).join();
        int[] cells = IntStream.range(0, 1440).toArray(); // 10% of 14,400
        when(footprints.load(eq(before.id()), any()))
                .thenReturn(List.of(Footprint.ofCells(cells, before.geometry())));

        PageSnapshot after = bootstrap.recover();

        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.occupancyPermille()).isEqualTo(100);
    }

    @Test
    void restartAfterTheScheduledEndTurnsThePageAtOnce() {
        PageSnapshot before = processor.submit(new PageCommand.GetPageSnapshot()).join();
        clock.set(before.scheduledEndAt().plus(Duration.ofHours(1)));

        PageSnapshot after = bootstrap.recover();

        assertThat(after.seq()).isEqualTo(before.seq() + 1);
        assertThat(after.status()).isEqualTo(PageStatus.ACTIVE);
        Page ended = pages.findById(before.id()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(PageStatus.ENDED);
        assertThat(ended.getEndedAt()).isEqualTo(clock.instant());
    }
}
