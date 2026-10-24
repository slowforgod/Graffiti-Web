package kr.ac.khu.graffiti.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletionException;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.doodle.StoredFootprint;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageReader;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.page.PostStore;
import org.springframework.stereotype.Service;

/**
 * Admin decisions on doodles moderation kept off the canvas (D-23): publish a HELD or REJECTED one, reject a
 * HELD one. State changes go through the page queue (INV-01) and leave a {@code moderation_action} (SEC-14).
 */
@Service
class AdminReviewService {

    private final AdminDoodleQuery query;
    private final PageCommandProcessor processor;
    private final PageReader pages;
    private final ObjectMapper json;

    AdminReviewService(AdminDoodleQuery query, PageCommandProcessor processor, PageReader pages, ObjectMapper json) {
        this.query = query;
        this.processor = processor;
        this.pages = pages;
        this.json = json;
    }

    /** Publishes a HELD or REJECTED doodle of the current page; {@code PAGE_ENDED} for an ended page. */
    AdminDoodleQuery.Stored publish(long doodleId, long adminId) {
        AdminDoodleQuery.Stored d = require(doodleId);
        if (!"HELD".equals(d.status()) && !"REJECTED".equals(d.status())) {
            throw new ApiException(ErrorCode.STATE_CONFLICT);
        }
        // The page's cells are final once it ends, so a late doodle would change its record (07 draft, 결정 4).
        PageSnapshot page = pages.requireCurrent(d.pageId());
        PostStore.NewDoodle row = new PostStore.NewDoodle(d.pageId(), d.authorId(), d.kind(), null, null, d.x(),
                d.y(), d.w(), d.h(), d.payload(), 0, null, d.chunks());
        await(new PageCommand.PublishWithheld(d.id(), row, d.status(),
                StoredFootprint.of(d.kind(), d.payload(), d.x(), d.y(), d.w(), d.h(), page.geometry(), json),
                adminId));
        return d;
    }

    void reject(long doodleId, long adminId) {
        AdminDoodleQuery.Stored d = require(doodleId);
        if (!"HELD".equals(d.status()) || !Boolean.TRUE.equals(
                await(new PageCommand.RejectHeld(d.id(), d.authorId(), adminId)))) {
            throw new ApiException(ErrorCode.STATE_CONFLICT);
        }
    }

    private AdminDoodleQuery.Stored require(long doodleId) {
        return query.stored(doodleId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private <R> R await(PageCommand<R> command) {
        try {
            return processor.submit(command).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
