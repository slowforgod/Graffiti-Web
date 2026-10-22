package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.PageReader;
import kr.ac.khu.graffiti.page.PageSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/pages/{pageId}/doodles?chunks=} (API.md §2), public, current page only (D-17). */
@RestController
class ChunkDoodleController {

    private final PageReader pages;
    private final ChunkDoodleQuery query;
    private final int maxChunks;

    ChunkDoodleController(PageReader pages, ChunkDoodleQuery query, GraffitiProperties props) {
        this.pages = pages;
        this.query = query;
        this.maxChunks = props.query().maxChunks();
    }

    @GetMapping("/api/pages/{pageId}/doodles")
    Response doodles(@PathVariable long pageId, @RequestParam(required = false) String chunks) {
        PageSnapshot page = pages.requireCurrent(pageId);
        int chunkCount = (page.geometry().width() / page.chunkSize()) * (page.geometry().height() / page.chunkSize());
        List<Integer> indices;
        try {
            indices = ChunkList.parse(chunks, chunkCount, maxChunks);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "chunks", "reason", e.getMessage()));
        }
        List<Doodle> doodles = query.find(pageId, indices).stream()
                .map(r -> new Doodle(Long.toString(r.id()), r.kind(), new Bbox(r.x(), r.y(), r.w(), r.h()),
                        r.payload()))
                .toList();
        return new Response(Long.toString(pageId), doodles);
    }

    record Response(String pageId, List<Doodle> doodles) {
    }

    /** The public shape of a doodle (API.md §2). No author fields (D-22). */
    record Doodle(String id, String kind, Bbox bbox, @JsonRawValue String payload) {
    }

    record Bbox(int x, int y, int w, int h) {
    }
}
