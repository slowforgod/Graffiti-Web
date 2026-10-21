package kr.ac.khu.graffiti.page;

import java.time.Instant;

/** Immutable view of the current page for readers outside the queue. */
public record PageSnapshot(
        long id,
        int seq,
        PageStatus status,
        int occupancyPermille,
        Instant createdAt,
        Instant countdownStartedAt,
        Instant scheduledEndAt,
        CellGeometry geometry,
        int chunkSize) {
}
