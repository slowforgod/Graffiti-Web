package kr.ac.khu.graffiti.realtime;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Answers a SUBSCRIBE that carries a {@code receipt} header once the broker has registered it. The
 * in-memory broker does not send receipts itself, and the client must not query REST before its
 * subscription is live, or events in between are lost (INV-09).
 */
@Component
class SubscribeReceipts implements ExecutorChannelInterceptor {

    private static final byte[] EMPTY = new byte[0];

    private final MessageChannel clientOutboundChannel;

    SubscribeReceipts(@Lazy MessageChannel clientOutboundChannel) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception ex) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (ex != null || stomp == null || stomp.getCommand() != StompCommand.SUBSCRIBE || stomp.getReceipt() == null
                || stomp.getDestination() == null) {
            return;
        }
        // /user/... is translated by the user destination handler, which hands it to the broker synchronously.
        boolean registeredBy = stomp.getDestination().startsWith("/user/")
                ? handler instanceof UserDestinationMessageHandler
                : handler instanceof SimpleBrokerMessageHandler;
        if (!registeredBy) {
            return;
        }
        StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
        receipt.setReceiptId(stomp.getReceipt());
        receipt.setSessionId(stomp.getSessionId());
        clientOutboundChannel.send(MessageBuilder.createMessage(EMPTY, receipt.getMessageHeaders()));
    }
}
