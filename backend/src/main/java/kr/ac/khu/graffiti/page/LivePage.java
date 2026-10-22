package kr.ac.khu.graffiti.page;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import kr.ac.khu.graffiti.page.event.PageTurned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cached view of the current page for frequent checks (every STOMP SUBSCRIBE and SEND, INV-04).
 * Page events only invalidate the cache: they run on the queue thread, which must not wait for the
 * queue. The next check reads the page through {@link PageReader} from its own thread.
 */
@Component
public class LivePage {

    private record Entry(long generation, PageSnapshot snapshot) {
    }

    private final PageReader reader;
    private final AtomicLong generation = new AtomicLong();
    private volatile Entry entry;

    LivePage(PageReader reader) {
        this.reader = reader;
    }

    /** The current page. Must not be called on the page queue thread. */
    public PageSnapshot current() {
        long g = generation.get();
        Entry e = entry;
        if (e != null && e.generation() == g) {
            return e.snapshot();
        }
        return refresh(g);
    }

    /** The current page if it is {@code pageId}. A newer id than the cached one is checked against the queue. */
    public Optional<PageSnapshot> ifCurrent(long pageId) {
        long g = generation.get();
        PageSnapshot snapshot = current();
        if (snapshot.id() < pageId) {
            snapshot = refresh(g);
        }
        return snapshot.id() == pageId ? Optional.of(snapshot) : Optional.empty();
    }

    private PageSnapshot refresh(long g) {
        PageSnapshot snapshot = reader.current();
        // Stored under the generation seen before the read, so an event during the read forces another read.
        entry = new Entry(g, snapshot);
        return snapshot;
    }

    @EventListener
    void onStatusChanged(PageStatusChanged event) {
        generation.incrementAndGet();
    }

    @EventListener
    void onTurned(PageTurned event) {
        generation.incrementAndGet();
    }
}
