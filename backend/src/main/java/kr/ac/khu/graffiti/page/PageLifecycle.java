package kr.ac.khu.graffiti.page;

import java.time.Instant;

/** Page state rules (D-15, INV-03): one direction only, and the countdown is never cancelled. */
public final class PageLifecycle {

    private PageLifecycle() {
    }

    /** {@code min(created + maxLifetime, countdownStarted + countdownDuration)}. */
    public static Instant scheduledEnd(Instant createdAt, Instant countdownStartedAt, PageRules rules) {
        Instant lifetimeEnd = createdAt.plus(rules.maxLifetime());
        if (countdownStartedAt == null) {
            return lifetimeEnd;
        }
        Instant countdownEnd = countdownStartedAt.plus(rules.countdownDuration());
        return countdownEnd.isBefore(lifetimeEnd) ? countdownEnd : lifetimeEnd;
    }

    public static boolean shouldStartCountdown(PageStatus status, int occupancyPermille, PageRules rules) {
        return status == PageStatus.ACTIVE && occupancyPermille >= rules.countdownThresholdPermille();
    }

    public static PageEndReason endReason(Instant createdAt, Instant countdownStartedAt, PageRules rules) {
        if (countdownStartedAt == null) {
            return PageEndReason.MAX_LIFETIME;
        }
        Instant countdownEnd = countdownStartedAt.plus(rules.countdownDuration());
        return countdownEnd.isAfter(createdAt.plus(rules.maxLifetime()))
                ? PageEndReason.MAX_LIFETIME
                : PageEndReason.OCCUPANCY;
    }

    public static boolean canTransition(PageStatus from, PageStatus to) {
        return switch (from) {
            case ACTIVE -> to == PageStatus.COUNTDOWN || to == PageStatus.ENDED;
            case COUNTDOWN -> to == PageStatus.ENDED;
            case ENDED -> false;
        };
    }
}
