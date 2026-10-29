package kr.ac.khu.graffiti.page;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/API.md §2, public. Canvas, chunk and cell sizes and the turn rules come from the page row snapshot (D-06).
 */
@RestController
class PageController {

    private final PageReader pages;
    private final GraffitiProperties props;
    private final Clock clock;

    PageController(PageReader pages, GraffitiProperties props, Clock clock) {
        this.pages = pages;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/api/pages/current")
    CurrentPage current() {
        PageSnapshot page = pages.current();
        return new CurrentPage(Long.toString(page.id()), page.seq(), page.status(), page.occupancyPermille(),
                page.createdAt(), page.countdownStartedAt(), page.scheduledEndAt(), clock.instant(), config(page));
    }

    @GetMapping("/api/pages/{pageId}/minimap")
    MinimapResponse minimap(@PathVariable long pageId) {
        Minimap minimap = pages.minimap(pageId);
        return new MinimapResponse(minimap.cols(), minimap.rows(),
                Base64.getEncoder().encodeToString(minimap.bitmap()), minimap.occupancyPermille());
    }

    private ClientConfig config(PageSnapshot page) {
        GraffitiProperties.Doodle doodle = props.doodle();
        return new ClientConfig(page.geometry().width(), page.geometry().height(), page.chunkSize(),
                page.geometry().cellSize(), props.canvas().zoomMin(), props.canvas().zoomMax(),
                doodle.palette(), doodle.strokeWidth().min(), doodle.strokeWidth().max(),
                doodle.strokeWidth().defaultWidth(), doodle.fontSizes(), doodle.defaultFontSize(),
                doodle.fonts(), doodle.defaultFont(), doodle.textWidthPercent(), doodle.textMaxLength(), doodle.maxBbox(), props.query().maxChunks(),
                props.postCooldown().toSeconds(), props.page().countdownWarning().toSeconds(),
                doodle.draftIdleTimeout().toSeconds(), props.realtime().cursorInterval().toMillis(),
                props.realtime().draftInterval().toMillis(), props.realtime().heartbeat().toMillis(),
                page.rules().countdownThresholdPermille(), page.rules().countdownDuration().toSeconds());
    }

    record CurrentPage(String id, int seq, PageStatus status, int occupancyPermille, Instant createdAt,
            Instant countdownStartedAt,
            Instant scheduledEndAt, Instant serverTime, ClientConfig config) {
    }

    record ClientConfig(int width, int height, int chunkSize, int cellSize, double zoomMin, double zoomMax,
            List<String> palette, int strokeWidthMin, int strokeWidthMax, int defaultStrokeWidth,
            List<Integer> fontSizes, int defaultFontSize,
            List<String> fonts, String defaultFont, int textWidthPercent, int textMaxLength, int maxBbox, int maxChunks, long postCooldownSec, long countdownWarningSec,
            long draftIdleTimeoutSec, long cursorIntervalMs, long draftIntervalMs, long heartbeatMs,
            int countdownThresholdPermille, long countdownDurationSec) {
    }

    record MinimapResponse(int cols, int rows, String bitmap, int occupancyPermille) {
    }
}
