package kr.ac.khu.graffiti.realtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.LivePage;
import kr.ac.khu.graffiti.page.PageSnapshot;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Relays drafts to the chunks they touch (D-11, ARCHITECTURE.md §4.3) without storing them
 * (INV-06): the connection keeps only the open draft's id, kind, reached chunks and byte count. A
 * connection has one draft at a time; a message for another draft id cancels the open one. Drafts
 * change no page state, so they do not go through the page queue. The sender is already checked by
 * {@link RealtimeInterceptor}; refusals go to the sender only ({@link RealtimeErrorAdvice}).
 */
@Controller
class DraftController {

    private final LivePage livePage;
    private final Broadcaster broadcaster;
    private final ObjectReader reader;
    private final GraffitiProperties.Doodle doodle;

    DraftController(LivePage livePage, Broadcaster broadcaster, ObjectMapper mapper, GraffitiProperties props) {
        this.livePage = livePage;
        this.broadcaster = broadcaster;
        this.reader = mapper.readerFor(DraftMessage.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.doodle = props.doodle();
    }

    @MessageMapping("/draft")
    void draft(Message<byte[]> frame, SimpMessageHeaderAccessor headers) {
        byte[] body = frame.getPayload();
        DraftMessage message = parse(body);
        long pageId = CursorController.parsePageId(message.pageId());
        PageSnapshot page = livePage.ifCurrent(pageId).orElseThrow(() -> new ApiException(ErrorCode.PAGE_ENDED));
        DraftRules.Limits limits = new DraftRules.Limits(page.geometry().width(), page.geometry().height(),
                page.chunkSize(), doodle.palette().size(), doodle.strokeWidth().min(),
                doodle.strokeWidth().max(), doodle.maxBbox());
        DraftRules.Op op;
        try {
            op = DraftRules.check(message, limits);
        } catch (IllegalArgumentException e) {
            throw CursorController.invalid(e.getMessage());
        }

        ConnectionState state = ConnectionState.of(headers.getSessionAttributes());
        synchronized (state) {
            apply(state, pageId, message.draftId(), op, body.length, limits);
        }
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        ConnectionState.find(headers.getSessionAttributes()).ifPresent(state -> {
            synchronized (state) {
                if (state.draft != null) {
                    cancel(state);
                }
            }
        });
    }

    private void apply(ConnectionState state, long pageId, String draftId, DraftRules.Op op, int bytes,
            DraftRules.Limits limits) {
        // A frame of a draft this connection already cancelled arrived late: frames may be reordered.
        if (draftId.equals(state.cancelledDraftId)) {
            return;
        }
        ConnectionState.Draft draft = state.draft;
        if (op instanceof DraftRules.Cancel) {
            if (draft != null && draft.id.equals(draftId)) {
                cancel(state);
            } else {
                state.cancelledDraftId = draftId;
            }
            return;
        }
        if (draft != null && (!draft.id.equals(draftId) || draft.pageId != pageId)) {
            cancel(state);
            draft = null;
        }
        DraftRules.Kind kind = DraftRules.kindOf(op);
        if (draft != null && draft.kind != kind) {
            throw CursorController.invalid("op");
        }
        if (draft == null) {
            draft = new ConnectionState.Draft(draftId, pageId, kind);
        }
        if (draft.bytes + bytes > doodle.maxPayloadBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        draft.bytes += bytes;
        state.draft = draft;

        String sid = state.sid;
        switch (op) {
            case DraftRules.Points p -> send(draft, DraftRules.chunksOf(p.area(), limits),
                    RealtimeEvents.DraftUpdate.points(sid, draftId, p.strokeIndex(), p.color(), p.width(), p.points()));
            case DraftRules.Text t -> send(draft, DraftRules.chunksOf(t.box(), limits),
                    RealtimeEvents.DraftUpdate.text(sid, draftId,
                            new RealtimeEvents.Box(t.box().x(), t.box().y(), t.box().w(), t.box().h())));
            case DraftRules.Erase e -> send(draft, List.copyOf(draft.chunks),
                    RealtimeEvents.DraftUpdate.erase(sid, draftId, e.strokeIndex()));
            case DraftRules.Cancel c -> throw new IllegalStateException("handled above");
        }
    }

    private void send(ConnectionState.Draft draft, List<Integer> chunks, Object event) {
        draft.chunks.addAll(chunks);
        chunks.forEach(chunk -> broadcaster.toChunk(draft.pageId, chunk, event));
    }

    private void cancel(ConnectionState state) {
        ConnectionState.Draft draft = state.draft;
        RealtimeEvents.DraftCancel event = new RealtimeEvents.DraftCancel(state.sid, draft.id);
        draft.chunks.forEach(chunk -> broadcaster.toChunk(draft.pageId, chunk, event));
        state.cancelledDraftId = draft.id;
        state.draft = null;
    }

    private DraftMessage parse(byte[] body) {
        try {
            DraftMessage message = reader.readValue(body);
            if (message == null) {
                throw CursorController.invalid("body");
            }
            return message;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "body"));
        }
    }
}
