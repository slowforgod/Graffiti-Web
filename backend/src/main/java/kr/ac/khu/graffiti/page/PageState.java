package kr.ac.khu.graffiti.page;

import java.time.Instant;

/** In-memory state of the current page. Owned by the queue thread; replaced only after a commit. */
record PageState(
        long id,
        int seq,
        PageStatus status,
        Instant createdAt,
        Instant countdownStartedAt,
        Instant scheduledEndAt,
        PageRules rules,
        int chunkSize,
        OccupancyGrid grid) {

    static PageState of(Page page, OccupancyGrid grid) {
        return new PageState(page.getId(), page.getSeq(), page.getStatus(), page.getCreatedAt(),
                page.getCountdownStartedAt(), page.getScheduledEndAt(), page.rules(), page.getChunkSize(), grid);
    }

    PageState withGrid(OccupancyGrid newGrid) {
        return new PageState(id, seq, status, createdAt, countdownStartedAt, scheduledEndAt, rules, chunkSize,
                newGrid);
    }

    PageState withCountdown(Instant startedAt, Instant endAt, OccupancyGrid newGrid) {
        return new PageState(id, seq, PageStatus.COUNTDOWN, createdAt, startedAt, endAt, rules, chunkSize, newGrid);
    }

    PageSnapshot snapshot() {
        return new PageSnapshot(id, seq, status, grid.occupancyPermille(), createdAt, countdownStartedAt,
                scheduledEndAt, grid.geometry(), chunkSize, rules);
    }
}
