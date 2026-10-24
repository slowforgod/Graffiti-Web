package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.page.PageCommand;
import kr.ac.khu.graffiti.page.PageCommandProcessor;
import kr.ac.khu.graffiti.page.PageReader;
import kr.ac.khu.graffiti.page.PageSnapshot;
import org.springframework.stereotype.Service;

/**
 * The author's own doodles (D-13): list them on the current page, delete one or several. Rows are read and
 * footprints rebuilt here, outside the queue (INV-02); the state change runs in the queue (INV-01).
 */
@Service
class MyDoodleService {

    private final MyDoodleQuery query;
    private final PageReader pages;
    private final PageCommandProcessor processor;
    private final ObjectMapper json;

    MyDoodleService(MyDoodleQuery query, PageReader pages, PageCommandProcessor processor, ObjectMapper json) {
        this.query = query;
        this.pages = pages;
        this.processor = processor;
        this.json = json;
    }

    List<MyDoodleQuery.Mine> mine(long authorId, long pageId) {
        pages.requireCurrent(pageId);
        return query.mine(authorId, pageId);
    }

    /**
     * Deletes all of {@code ids} or none (API.md §3): {@code NOT_FOUND} when one does not exist, {@code FORBIDDEN}
     * when one is someone else's, {@code PAGE_ENDED} when one is not on the current page, {@code STATE_CONFLICT}
     * when one is not on the canvas.
     */
    void delete(long authorId, List<Long> ids) {
        List<MyDoodleQuery.Stored> rows = query.stored(ids);
        if (rows.size() != ids.size()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (rows.stream().anyMatch(r -> r.authorId() != authorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        PageSnapshot page = pages.current();
        if (rows.stream().anyMatch(r -> r.pageId() != page.id())) {
            throw new ApiException(ErrorCode.PAGE_ENDED);
        }
        if (rows.stream().anyMatch(r -> !"PUBLISHED".equals(r.status()))) {
            throw new ApiException(ErrorCode.STATE_CONFLICT);
        }
        Map<Long, List<Integer>> chunks = query.chunks(ids);
        List<PageCommand.Removal> removals = rows.stream()
                .map(r -> new PageCommand.Removal(r.id(), StoredFootprint.of(r.kind(), r.payload(), r.x(), r.y(),
                        r.w(), r.h(), page.geometry(), json), chunks.getOrDefault(r.id(), List.of())))
                .toList();
        await(new PageCommand.RemoveDoodles(page.id(), authorId, removals));
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
