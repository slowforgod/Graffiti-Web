package kr.ac.khu.graffiti.page.event;

import java.time.Instant;
import kr.ac.khu.graffiti.page.PageStatus;

/** Published after commit when a page becomes current or its status changes. */
public record PageStatusChanged(
        long pageId,
        PageStatus status,
        int occupancyPermille,
        Instant countdownStartedAt,
        Instant scheduledEndAt) {
}
