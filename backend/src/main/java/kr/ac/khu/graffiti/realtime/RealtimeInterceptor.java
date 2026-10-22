package kr.ac.khu.graffiti.realtime;

import java.time.Clock;
import kr.ac.khu.graffiti.auth.Access;
import kr.ac.khu.graffiti.auth.AccessCheck;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.page.LivePage;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Checks every inbound STOMP frame like a REST request (SEC-07): what may be subscribed, who may
 * send, how often (SEC-08), and whether the page is current (INV-04). A refusal throws
 * {@link ApiException}; {@link StompErrorHandler} turns it into an ERROR frame with the code.
 */
@Component
class RealtimeInterceptor implements ChannelInterceptor {

    private final LivePage livePage;
    private final UserAccountService accounts;
    private final SendRateLimiter rateLimiter;
    private final int maxSubscriptions;

    RealtimeInterceptor(LivePage livePage, UserAccountService accounts, GraffitiProperties props, Clock clock) {
        this.livePage = livePage;
        this.accounts = accounts;
        this.rateLimiter = new SendRateLimiter(props.realtime().maxMessagesPerSecond(), clock);
        this.maxSubscriptions = props.realtime().maxSubscriptions();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message;
        }
        StompCommand command = stomp.getCommand();
        if (command == StompCommand.CONNECT) {
            ConnectionState.of(stomp.getSessionAttributes());
        } else if (command == StompCommand.SUBSCRIBE) {
            subscribe(stomp);
        } else if (command == StompCommand.UNSUBSCRIBE) {
            ConnectionState.of(stomp.getSessionAttributes()).subscriptions.remove(stomp.getSubscriptionId());
        } else if (command == StompCommand.SEND) {
            send(stomp);
        }
        return message;
    }

    private void subscribe(StompHeaderAccessor stomp) {
        switch (RealtimeDestinations.parse(stomp.getDestination())) {
            case RealtimeDestinations.Chunk c -> {
                PageSnapshot page = requireCurrent(c.pageId());
                int chunks = (page.geometry().width() / page.chunkSize()) * (page.geometry().height() / page.chunkSize());
                if (c.index() >= chunks) {
                    throw new ApiException(ErrorCode.FORBIDDEN);
                }
            }
            case RealtimeDestinations.Meta m -> requireCurrent(m.pageId());
            case RealtimeDestinations.UserQueue q -> {
                if (ConnectionState.userId(stomp.getUser()).isEmpty()) {
                    throw new ApiException(ErrorCode.UNAUTHENTICATED);
                }
            }
            case RealtimeDestinations.Unknown u -> throw new ApiException(ErrorCode.FORBIDDEN);
        }
        ConnectionState state = ConnectionState.of(stomp.getSessionAttributes());
        String id = stomp.getSubscriptionId();
        if (id == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        // Checked and added together per connection; frames of one connection arrive in order.
        if (!state.subscriptions.containsKey(id) && state.subscriptions.size() >= maxSubscriptions) {
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        state.subscriptions.put(id, stomp.getDestination());
    }

    private void send(StompHeaderAccessor stomp) {
        String destination = stomp.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            // Clients never publish straight to the broker.
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        long userId = ConnectionState.userId(stomp.getUser())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        // Read on every SEND so a ban or a lost consent applies at once (SEC-15).
        User user = accounts.find(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        AccessCheck.check(user, Access.AUTHOR);
        if (!rateLimiter.tryAcquire(userId)) {
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        ConnectionState.of(stomp.getSessionAttributes()).author =
                new ConnectionState.Author(user.getNickname(), user.getCursorColor());
    }

    private PageSnapshot requireCurrent(long pageId) {
        return livePage.ifCurrent(pageId).orElseThrow(() -> new ApiException(ErrorCode.PAGE_ENDED));
    }
}
