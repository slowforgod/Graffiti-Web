package kr.ac.khu.graffiti.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import kr.ac.khu.graffiti.support.MutableClock;
import org.junit.jupiter.api.Test;

class SendRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00.100Z"));
    private final SendRateLimiter limiter = new SendRateLimiter(20, clock);

    @Test
    void allowsTwentyPerSecondAndRefusesTheTwentyFirst() {
        assertThat(IntStream.range(0, 20).allMatch(i -> limiter.tryAcquire(1))).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    void countsUsersSeparately() {
        IntStream.range(0, 21).forEach(i -> limiter.tryAcquire(1));
        assertThat(limiter.tryAcquire(2)).isTrue();
    }

    @Test
    void startsAgainInTheNextSecond() {
        IntStream.range(0, 21).forEach(i -> limiter.tryAcquire(1));
        clock.set(Instant.parse("2026-10-01T00:00:01.000Z"));
        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void forgetsIdleUsers() {
        limiter.tryAcquire(1);
        limiter.tryAcquire(2);
        clock.set(clock.instant().plus(Duration.ofMinutes(2)));
        limiter.tryAcquire(3);
        assertThat(limiter.trackedUsers()).isEqualTo(1);
    }
}
