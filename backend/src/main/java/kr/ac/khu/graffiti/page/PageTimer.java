package kr.ac.khu.graffiti.page;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.page.event.PageStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Keeps one {@code EndPage} scheduled at the current page's {@code scheduledEndAt}. Every status
 * event (page installed, countdown started, new page) replaces the previous schedule.
 */
@Component
class PageTimer {

    private static final Logger log = LoggerFactory.getLogger(PageTimer.class);

    private final TaskScheduler scheduler;
    private final PageCommandProcessor processor;

    private ScheduledFuture<?> pending;

    PageTimer(@Qualifier(PageConfig.PAGE_SCHEDULER) TaskScheduler scheduler, PageCommandProcessor processor) {
        this.scheduler = scheduler;
        this.processor = processor;
    }

    @EventListener
    public void onStatusChanged(PageStatusChanged event) {
        schedule(event.pageId(), event.scheduledEndAt());
    }

    synchronized void schedule(long pageId, Instant endAt) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(() -> fire(pageId), endAt);
    }

    private void fire(long pageId) {
        processor.submit(new PageCommand.EndPage(pageId)).whenComplete((snapshot, error) -> {
            if (error == null) {
                return;
            }
            if (error.getCause() instanceof ApiException) {
                // Page already turned (e.g. a duplicate timer). Nothing to do.
                log.debug("Ignored end timer for page {}", pageId);
            } else {
                log.error("Failed to end page {}", pageId, error);
            }
        });
    }
}
