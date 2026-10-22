package kr.ac.khu.graffiti.realtime;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code limit} STOMP SENDs per user per wall-clock second (SEC-08, D-12). A fixed window
 * keeps one small entry per active user; idle entries are swept once a minute.
 */
public final class SendRateLimiter {

    private static final long SWEEP_EVERY_SECONDS = 60;

    private record Window(long second, int count) {
    }

    private final int limit;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    public SendRateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    public boolean tryAcquire(long userId) {
        long second = Math.floorDiv(clock.millis(), 1000);
        sweep(second);
        Window window = windows.compute(userId, (id, w) -> w == null || w.second() != second
                ? new Window(second, 1)
                : new Window(second, w.count() + 1));
        return window.count() <= limit;
    }

    int trackedUsers() {
        return windows.size();
    }

    private void sweep(long second) {
        if (second - lastSweep < SWEEP_EVERY_SECONDS) {
            return;
        }
        lastSweep = second;
        windows.values().removeIf(w -> w.second() < second);
    }
}
