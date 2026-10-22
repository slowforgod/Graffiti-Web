package kr.ac.khu.graffiti.realtime;

import java.util.Map;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.page.LivePage;
import kr.ac.khu.graffiti.page.PageSnapshot;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Relays cursors to the chunk under them (API.md §6). Nothing is stored except the chunk of the
 * connection's last cursor, used to send {@code cursor:leave} when it moves away or disconnects.
 * The sender is already checked by {@link RealtimeInterceptor}.
 */
@Controller
class CursorController {

    record CursorMessage(String pageId, Integer x, Integer y) {
    }

    private final LivePage livePage;
    private final Broadcaster broadcaster;

    CursorController(LivePage livePage, Broadcaster broadcaster) {
        this.livePage = livePage;
        this.broadcaster = broadcaster;
    }

    @MessageMapping("/cursor")
    void cursor(@Payload CursorMessage message, SimpMessageHeaderAccessor headers) {
        long pageId = parsePageId(message.pageId());
        PageSnapshot page = livePage.ifCurrent(pageId).orElseThrow(() -> new ApiException(ErrorCode.PAGE_ENDED));
        int width = page.geometry().width();
        int height = page.geometry().height();
        if (message.x() == null || message.y() == null
                || message.x() < 0 || message.x() >= width || message.y() < 0 || message.y() >= height) {
            throw invalid("x,y");
        }
        int x = message.x();
        int y = message.y();
        int chunk = (y / page.chunkSize()) * (width / page.chunkSize()) + x / page.chunkSize();

        ConnectionState state = ConnectionState.of(headers.getSessionAttributes());
        ConnectionState.CursorAt previous = state.cursor;
        if (previous != null && (previous.pageId() != pageId || previous.chunk() != chunk)) {
            broadcaster.toChunk(previous.pageId(), previous.chunk(), new RealtimeEvents.CursorLeave(state.sid));
        }
        state.cursor = new ConnectionState.CursorAt(pageId, chunk);
        ConnectionState.Author author = state.author;
        broadcaster.toChunk(pageId, chunk,
                new RealtimeEvents.Cursor(state.sid, author.nickname(), author.color(), x, y));
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        ConnectionState.find(headers.getSessionAttributes()).ifPresent(state -> {
            ConnectionState.CursorAt last = state.cursor;
            if (last != null) {
                broadcaster.toChunk(last.pageId(), last.chunk(), new RealtimeEvents.CursorLeave(state.sid));
            }
        });
    }

    // A refused cursor keeps the connection open: the error goes to this connection only.
    @MessageExceptionHandler(ApiException.class)
    @SendToUser(destinations = RealtimeDestinations.ERRORS_QUEUE, broadcast = false)
    RealtimeEvents.ErrorEvent onRefused(ApiException e) {
        return new RealtimeEvents.ErrorEvent(e.code().name());
    }

    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(destinations = RealtimeDestinations.ERRORS_QUEUE, broadcast = false)
    RealtimeEvents.ErrorEvent onUnreadable(MessageConversionException e) {
        return new RealtimeEvents.ErrorEvent(ErrorCode.VALIDATION_FAILED.name());
    }

    private static long parsePageId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 18 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw invalid("pageId");
        }
        return Long.parseLong(raw);
    }

    private static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", field));
    }
}
