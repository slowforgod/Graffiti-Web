package kr.ac.khu.graffiti.realtime;

import kr.ac.khu.graffiti.page.event.DoodlePublished;
import kr.ac.khu.graffiti.page.event.OccupancyChanged;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import kr.ac.khu.graffiti.page.event.PageTurned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Relays committed page events to the meta topic, and published doodles to their chunk topics. Runs on
 * the page queue thread, so it only hands messages to the broker and never waits for the queue (INV-02).
 */
@Component
class PageEventRelay {

    private final Broadcaster broadcaster;

    PageEventRelay(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @EventListener
    void onStatus(PageStatusChanged e) {
        broadcaster.toMeta(e.pageId(), new RealtimeEvents.PageStatusEvent(e.status(), e.occupancyPermille(),
                e.countdownStartedAt(), e.scheduledEndAt()));
    }

    @EventListener
    void onOccupancy(OccupancyChanged e) {
        broadcaster.toMeta(e.pageId(), new RealtimeEvents.MinimapDiff(e.set(), e.clear(), e.occupancyPermille()));
    }

    @EventListener
    void onPublished(DoodlePublished e) {
        RealtimeEvents.DoodlePublishedEvent event = new RealtimeEvents.DoodlePublishedEvent(
                new RealtimeEvents.PublishedDoodle(Long.toString(e.doodleId()), e.kind(),
                        new RealtimeEvents.Box(e.x(), e.y(), e.w(), e.h()), e.payload()));
        e.chunks().forEach(chunk -> broadcaster.toChunk(e.pageId(), chunk, event));
    }

    @EventListener
    void onTurned(PageTurned e) {
        broadcaster.toMeta(e.oldPageId(), new RealtimeEvents.PageTurnedEvent(Long.toString(e.newPageId())));
    }
}
