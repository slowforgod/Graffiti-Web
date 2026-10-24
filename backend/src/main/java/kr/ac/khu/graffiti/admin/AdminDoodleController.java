package kr.ac.khu.graffiti.admin;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.auth.Access;
import kr.ac.khu.graffiti.auth.AuthUser;
import kr.ac.khu.graffiti.auth.CurrentUser;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The review part of {@code /api/admin/**} (API.md §4): held, rejected and unchecked doodles, the action log. */
@RestController
class AdminDoodleController {

    record Page<T>(List<T> items, int page, int size, long total) {
    }

    record Bbox(int x, int y, int w, int h) {
    }

    record Author(String id, String nickname, String email) {
    }

    record ReviewDoodle(String id, String pageId, boolean pageEnded, String kind, Bbox bbox,
            @JsonRawValue String payload, String status, String moderationState,
            @JsonRawValue String moderationDetail, Instant submittedAt, Author author) {
    }

    record Doodle(String id, String kind, Bbox bbox, @JsonRawValue String payload) {
    }

    record Published(Doodle doodle) {
    }

    record Rejected(String id, String status) {
    }

    record Action(String id, String actorType, String actorId, String action, String targetDoodleId,
            String targetUserId, String reason, @JsonRawValue String detail, Instant createdAt) {
    }

    private final AdminDoodleQuery query;
    private final AdminReviewService review;
    private final int maxSize;

    AdminDoodleController(AdminDoodleQuery query, AdminReviewService review, GraffitiProperties props) {
        this.query = query;
        this.review = review;
        this.maxSize = props.query().adminPageSizeMax();
    }

    @GetMapping("/api/admin/doodles")
    Page<ReviewDoodle> doodles(@CurrentUser(Access.ADMIN) AuthUser admin,
            @RequestParam(defaultValue = "HELD") String moderation,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        AdminDoodleQuery.Filter filter = filter(moderation);
        checkWindow(page, size);
        List<ReviewDoodle> items = query.list(filter, page, size).stream()
                .map(r -> new ReviewDoodle(Long.toString(r.id()), Long.toString(r.pageId()), r.pageEnded(), r.kind(),
                        new Bbox(r.x(), r.y(), r.w(), r.h()), r.payload(), r.status(), r.moderationState(),
                        r.moderationDetail(), r.submittedAt(),
                        new Author(Long.toString(r.authorId()), r.nickname(), r.email())))
                .toList();
        return new Page<>(items, page, size, query.count(filter));
    }

    @PostMapping("/api/admin/doodles/{id}/restore")
    Published restore(@CurrentUser(Access.ADMIN) AuthUser admin, @PathVariable long id) {
        AdminDoodleQuery.Stored d = review.publish(id, admin.id());
        return new Published(new Doodle(Long.toString(d.id()), d.kind(), new Bbox(d.x(), d.y(), d.w(), d.h()),
                d.payload()));
    }

    @PostMapping("/api/admin/doodles/{id}/reject")
    Rejected reject(@CurrentUser(Access.ADMIN) AuthUser admin, @PathVariable long id) {
        review.reject(id, admin.id());
        return new Rejected(Long.toString(id), "REJECTED");
    }

    @GetMapping("/api/admin/actions")
    Page<Action> actions(@CurrentUser(Access.ADMIN) AuthUser admin, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        checkWindow(page, size);
        List<Action> items = query.actions(page, size).stream()
                .map(a -> new Action(Long.toString(a.id()), a.actorType(), str(a.actorId()), a.action(),
                        str(a.targetDoodleId()), str(a.targetUserId()), a.reason(), a.detail(), a.createdAt()))
                .toList();
        return new Page<>(items, page, size, query.countActions());
    }

    private static AdminDoodleQuery.Filter filter(String moderation) {
        try {
            return AdminDoodleQuery.Filter.valueOf(moderation);
        } catch (IllegalArgumentException e) {
            throw invalid("moderation");
        }
    }

    private void checkWindow(int page, int size) {
        if (page < 0) {
            throw invalid("page");
        }
        if (size < 1 || size > maxSize) {
            throw invalid("size");
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", field, "reason", "not_allowed"));
    }

    private static String str(Long id) {
        return id == null ? null : Long.toString(id);
    }
}
