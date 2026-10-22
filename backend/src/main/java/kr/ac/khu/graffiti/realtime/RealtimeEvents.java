package kr.ac.khu.graffiti.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import kr.ac.khu.graffiti.page.PageStatus;

/** Message bodies of API.md §6. {@code sid} is a per-connection id, never the user id (D-22). */
public final class RealtimeEvents {

    private RealtimeEvents() {
    }

    public record Cursor(String type, String sid, String nickname, String color, int x, int y) {

        public Cursor(String sid, String nickname, String color, int x, int y) {
            this("cursor", sid, nickname, color, x, y);
        }
    }

    public record CursorLeave(String type, String sid) {

        public CursorLeave(String sid) {
            this("cursor:leave", sid);
        }
    }

    /** One draft change; only the fields of its {@code op} are present (D-11, D-33: no text). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DraftUpdate(String type, String sid, String draftId, String op, Integer strokeIndex, Integer c,
            Integer w, int[] points, Box bbox) {

        public static DraftUpdate points(String sid, String draftId, int strokeIndex, int c, int w, int[] points) {
            return new DraftUpdate("draft:update", sid, draftId, "points", strokeIndex, c, w, points, null);
        }

        public static DraftUpdate erase(String sid, String draftId, int strokeIndex) {
            return new DraftUpdate("draft:update", sid, draftId, "erase", strokeIndex, null, null, null, null);
        }

        public static DraftUpdate text(String sid, String draftId, Box bbox) {
            return new DraftUpdate("draft:update", sid, draftId, "text", null, null, null, null, bbox);
        }
    }

    public record Box(int x, int y, int w, int h) {
    }

    public record DraftCancel(String type, String sid, String draftId) {

        public DraftCancel(String sid, String draftId) {
            this("draft:cancel", sid, draftId);
        }
    }

    public record PageStatusEvent(String type, PageStatus status, int occupancyPermille, Instant countdownStartedAt,
            Instant scheduledEndAt) {

        public PageStatusEvent(PageStatus status, int occupancyPermille, Instant countdownStartedAt,
                Instant scheduledEndAt) {
            this("page:status", status, occupancyPermille, countdownStartedAt, scheduledEndAt);
        }
    }

    public record MinimapDiff(String type, List<Integer> set, List<Integer> clear, int occupancyPermille) {

        public MinimapDiff(List<Integer> set, List<Integer> clear, int occupancyPermille) {
            this("minimap:diff", set, clear, occupancyPermille);
        }
    }

    public record PageTurnedEvent(String type, String newPageId) {

        public PageTurnedEvent(String newPageId) {
            this("page:turned", newPageId);
        }
    }

    /** Sent to the sender's {@code /user/queue/errors} when a SEND is refused after it was accepted. */
    public record ErrorEvent(String type, String code) {

        public ErrorEvent(String code) {
            this("error", code);
        }
    }
}
