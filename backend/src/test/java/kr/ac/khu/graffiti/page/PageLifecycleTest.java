package kr.ac.khu.graffiti.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PageLifecycleTest {

    private static final PageRules RULES = new PageRules(700, Duration.ofHours(6), Duration.ofDays(7));
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void newPageEndsAtMaxLifetime() {
        assertThat(PageLifecycle.scheduledEnd(CREATED, null, RULES)).isEqualTo(CREATED.plus(Duration.ofDays(7)));
    }

    @Test
    void countdownEndsSixHoursAfterItStarts() {
        Instant started = CREATED.plus(Duration.ofDays(2));

        assertThat(PageLifecycle.scheduledEnd(CREATED, started, RULES)).isEqualTo(started.plus(Duration.ofHours(6)));
    }

    @Test
    void countdownNeverExtendsPastMaxLifetime() {
        Instant started = CREATED.plus(Duration.ofDays(7)).minus(Duration.ofHours(1));

        assertThat(PageLifecycle.scheduledEnd(CREATED, started, RULES)).isEqualTo(CREATED.plus(Duration.ofDays(7)));
    }

    @Test
    void countdownStartsOnlyFromActiveAtThreshold() {
        assertThat(PageLifecycle.shouldStartCountdown(PageStatus.ACTIVE, 699, RULES)).isFalse();
        assertThat(PageLifecycle.shouldStartCountdown(PageStatus.ACTIVE, 700, RULES)).isTrue();
        assertThat(PageLifecycle.shouldStartCountdown(PageStatus.COUNTDOWN, 900, RULES)).isFalse();
        assertThat(PageLifecycle.shouldStartCountdown(PageStatus.ENDED, 900, RULES)).isFalse();
    }

    @Test
    void endReasonIsOccupancyWhenTheCountdownDecidedTheEnd() {
        Instant started = CREATED.plus(Duration.ofDays(1));

        assertThat(PageLifecycle.endReason(CREATED, started, RULES)).isEqualTo(PageEndReason.OCCUPANCY);
    }

    @Test
    void endReasonIsMaxLifetimeWithoutCountdownOrWhenLifetimeCameFirst() {
        assertThat(PageLifecycle.endReason(CREATED, null, RULES)).isEqualTo(PageEndReason.MAX_LIFETIME);
        Instant lateStart = CREATED.plus(Duration.ofDays(7)).minus(Duration.ofHours(1));
        assertThat(PageLifecycle.endReason(CREATED, lateStart, RULES)).isEqualTo(PageEndReason.MAX_LIFETIME);
    }

    @Test
    void transitionsOnlyMoveForward() {
        assertThat(PageLifecycle.canTransition(PageStatus.ACTIVE, PageStatus.COUNTDOWN)).isTrue();
        assertThat(PageLifecycle.canTransition(PageStatus.ACTIVE, PageStatus.ENDED)).isTrue();
        assertThat(PageLifecycle.canTransition(PageStatus.COUNTDOWN, PageStatus.ENDED)).isTrue();
        assertThat(PageLifecycle.canTransition(PageStatus.COUNTDOWN, PageStatus.ACTIVE)).isFalse();
        assertThat(PageLifecycle.canTransition(PageStatus.ENDED, PageStatus.ACTIVE)).isFalse();
        assertThat(PageLifecycle.canTransition(PageStatus.ENDED, PageStatus.COUNTDOWN)).isFalse();
        assertThat(PageLifecycle.canTransition(PageStatus.ACTIVE, PageStatus.ACTIVE)).isFalse();
    }

    @Test
    void rulesConvertThresholdToPermille() {
        assertThat(PageRules.thresholdToPermille(0.70)).isEqualTo(700);
        assertThat(PageRules.thresholdToPermille(0.655)).isEqualTo(655);
    }
}
