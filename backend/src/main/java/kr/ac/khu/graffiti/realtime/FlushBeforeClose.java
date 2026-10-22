package kr.ac.khu.graffiti.realtime;

import java.io.IOException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * Makes an ERROR frame reach the client before its connection closes, so the client gets the error
 * code (API.md §6). Spring closes the connection right after sending ERROR. Its session decorator only
 * buffers a frame while another thread is still writing (for example CONNECTED), and closing then
 * drops the buffer: the client saw the connection end with no code. Here a close waits its turn behind
 * the buffered frames. Installed by swapping the {@link SubProtocolWebSocketHandler} that
 * {@code @EnableWebSocketMessageBroker} creates, which has no other way to change its sessions.
 */
@Component
class FlushBeforeClose implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean.getClass() != SubProtocolWebSocketHandler.class) {
            return bean;
        }
        Handler handler = new Handler(beanFactory.getBean("clientInboundChannel", MessageChannel.class),
                beanFactory.getBean("clientOutboundChannel", SubscribableChannel.class));
        handler.setPhase(((SubProtocolWebSocketHandler) bean).getPhase());
        return handler;
    }

    static class Handler extends SubProtocolWebSocketHandler {

        Handler(MessageChannel clientInboundChannel, SubscribableChannel clientOutboundChannel) {
            super(clientInboundChannel, clientOutboundChannel);
        }

        @Override
        protected WebSocketSession decorateSession(WebSocketSession session) {
            return new Session(session, getSendTimeLimit(), getSendBufferSizeLimit());
        }
    }

    // Spring's decorator in front of the socket. A close goes into its buffer like a frame, so it happens
    // only after the frames buffered before it are written, by whichever thread is writing.
    static class Session extends ConcurrentWebSocketSessionDecorator {

        // A STOMP heart-beat: clients ignore it, and sending it flushes what is buffered.
        private static final TextMessage HEARTBEAT = new TextMessage("\n");

        Session(WebSocketSession socket, int sendTimeLimit, int bufferSizeLimit) {
            super(new InOrder(socket), sendTimeLimit, bufferSizeLimit);
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            InOrder socket = (InOrder) getDelegate();
            if (isOpen()) {
                try {
                    super.sendMessage(new CloseAfter(status));
                    // A send that lost the race for the flush lock leaves the buffer behind until the next
                    // send. When no thread is writing, a heart-beat is that next send.
                    long deadline = System.currentTimeMillis() + getSendTimeLimit();
                    while (!socket.closed && System.currentTimeMillis() < deadline) {
                        if (getTimeSinceSendStarted() == 0) {
                            super.sendMessage(HEARTBEAT);
                        }
                        Thread.yield();
                    }
                } catch (IOException | SessionLimitExceededException e) {
                    // Closed below either way.
                }
            }
            super.close(status);
        }
    }

    // The socket under Spring's decorator: closes when the close reaches it in order, then drops the rest.
    static class InOrder extends WebSocketSessionDecorator {

        private volatile boolean closed;

        InOrder(WebSocketSession socket) {
            super(socket);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof CloseAfter close) {
                close(close.status);
            } else if (!closed) {
                super.sendMessage(message);
            }
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            if (!closed) {
                closed = true;
                super.close(status);
            }
        }
    }

    private record CloseAfter(CloseStatus status) implements WebSocketMessage<String> {

        @Override
        public String getPayload() {
            return "";
        }

        @Override
        public int getPayloadLength() {
            return 0;
        }

        @Override
        public boolean isLast() {
            return true;
        }
    }
}
