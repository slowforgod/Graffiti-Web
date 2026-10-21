package kr.ac.khu.graffiti.page;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * A page row. Rows are never deleted (INV-07); state changes go through conditional updates in
 * {@link PageRepository} called from the page command queue (INV-01, INV-03).
 */
@Entity
@Table(name = "page")
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int seq;

    @Enumerated(EnumType.STRING)
    private PageStatus status;

    private int width;
    private int height;
    private int chunkSize;
    private int cellSize;
    @Column(columnDefinition = "SMALLINT")
    private int countdownThresholdPermille;
    private int countdownDurationSec;
    private int maxLifetimeSec;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant createdAt;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant countdownStartedAt;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant scheduledEndAt;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    private PageEndReason endReason;

    @Column(columnDefinition = "SMALLINT")
    private Integer finalOccupancyPermille;

    @Column(columnDefinition = "VARBINARY(4096)")
    private byte[] finalOccupancyBitmap;

    protected Page() {
    }

    /** A new ACTIVE page with the current configuration snapshotted (D-06). */
    static Page newActive(int seq, CellGeometry geometry, int chunkSize, PageRules rules, Instant now) {
        Page page = new Page();
        page.seq = seq;
        page.status = PageStatus.ACTIVE;
        page.width = geometry.width();
        page.height = geometry.height();
        page.chunkSize = chunkSize;
        page.cellSize = geometry.cellSize();
        page.countdownThresholdPermille = rules.countdownThresholdPermille();
        page.countdownDurationSec = Math.toIntExact(rules.countdownDuration().toSeconds());
        page.maxLifetimeSec = Math.toIntExact(rules.maxLifetime().toSeconds());
        page.createdAt = now;
        page.scheduledEndAt = PageLifecycle.scheduledEnd(now, null, rules);
        return page;
    }

    public Long getId() {
        return id;
    }

    public int getSeq() {
        return seq;
    }

    public PageStatus getStatus() {
        return status;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public CellGeometry geometry() {
        return new CellGeometry(width, height, cellSize);
    }

    public PageRules rules() {
        return new PageRules(countdownThresholdPermille, Duration.ofSeconds(countdownDurationSec),
                Duration.ofSeconds(maxLifetimeSec));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCountdownStartedAt() {
        return countdownStartedAt;
    }

    public Instant getScheduledEndAt() {
        return scheduledEndAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public PageEndReason getEndReason() {
        return endReason;
    }

    public Integer getFinalOccupancyPermille() {
        return finalOccupancyPermille;
    }

    public byte[] getFinalOccupancyBitmap() {
        return finalOccupancyBitmap;
    }
}
