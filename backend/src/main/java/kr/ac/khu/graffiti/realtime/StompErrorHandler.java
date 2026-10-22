package kr.ac.khu.graffiti.realtime;

import java.nio.charset.StandardCharsets;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.util.MimeType;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * ERROR frames carry the REST error code in the {@code message} header (API.md §6) and the Korean
 * message as the body. Unexpected failures become {@code INTERNAL} without details (SEC-13).
 */
class StompErrorHandler extends StompSubProtocolErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(StompErrorHandler.class);

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        ErrorCode code = codeOf(ex);
        if (code == ErrorCode.INTERNAL) {
            log.error("STOMP frame failed", ex);
        }
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(code.name());
        error.setContentType(new MimeType("text", "plain", StandardCharsets.UTF_8));
        if (clientMessage != null) {
            StompHeaderAccessor client = MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
            if (client != null && client.getReceipt() != null) {
                error.setReceiptId(client.getReceipt());
            }
        }
        error.setLeaveMutable(true);
        return MessageBuilder.createMessage(code.defaultMessage().getBytes(StandardCharsets.UTF_8),
                error.getMessageHeaders());
    }

    static ErrorCode codeOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) {
                return api.code();
            }
        }
        return ErrorCode.INTERNAL;
    }
}
