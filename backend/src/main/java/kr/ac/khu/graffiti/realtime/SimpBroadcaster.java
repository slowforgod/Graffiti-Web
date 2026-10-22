package kr.ac.khu.graffiti.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Sends through the in-memory broker; delivery to sockets happens on the outbound channel's threads. */
@Component
class SimpBroadcaster implements Broadcaster {

    private final SimpMessagingTemplate template;

    SimpBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void toChunk(long pageId, int chunk, Object event) {
        template.convertAndSend(RealtimeDestinations.chunkTopic(pageId, chunk), event);
    }

    @Override
    public void toMeta(long pageId, Object event) {
        template.convertAndSend(RealtimeDestinations.metaTopic(pageId), event);
    }

    @Override
    public void toUser(long userId, Object event) {
        template.convertAndSendToUser(Long.toString(userId), RealtimeDestinations.NOTICE_QUEUE, event);
    }
}
