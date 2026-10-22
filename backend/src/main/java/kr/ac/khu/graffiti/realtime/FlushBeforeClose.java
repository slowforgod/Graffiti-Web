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
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * Makes an ERROR frame reach the client before its connection closes, so the client gets the error
 * code (API.md §6). Spring closes the connection right after sending ERROR. Its session decorator only
 * buffers a frame while another thread is still writing (for example CONNECTED), and closing then
 * drops the buffer: the client saw the connection end with no code. Here a close first sends what is
 * buffered. Installed by swapping the {@link SubProtocolWebSocketHandler} that
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

    static class Session extends ConcurrentWebSocketSessionDecorator {

        // A STOMP heart-beat: clients ignore it, and sending it flushes what is buffered.
        private static final TextMessage HEARTBEAT = new TextMessage("\n");

        Session(WebSocketSession delegate, int sendTimeLimit, int bufferSizeLimit) {
            super(delegate, sendTimeLimit, bufferSizeLimit);
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            flush();
            super.close(status);
        }

        // A thread that is writing sends the buffer when it finishes. When none is, the buffer may have
        // been left behind by a send that lost the race for the flush lock, so a heart-beat flushes it.
        private void flush() throws IOException {
            long deadline = System.currentTimeMillis() + getSendTimeLimit();
            while (getBufferSize() > 0 && isOpen() && System.currentTimeMillis() < deadline) {
                if (getTimeSinceSendStarted() == 0) {
                    try {
                        super.sendMessage(HEARTBEAT);
                    } catch (SessionLimitExceededException e) {
                        return;
                    }
                }
                Thread.yield();
            }
        }
    }
}
