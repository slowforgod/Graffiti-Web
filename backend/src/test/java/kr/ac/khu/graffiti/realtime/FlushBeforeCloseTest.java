package kr.ac.khu.graffiti.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class FlushBeforeCloseTest {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT = 64 * 1024;
    private static final int CLOSE_FIRST_MS = 200;

    private final List<String> written = new CopyOnWriteArrayList<>();
    private final CountDownLatch writing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    // A socket whose first write (CONNECTED) stays in progress until released.
    private WebSocketSession socket() throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("s1");
        when(socket.isOpen()).thenAnswer(i -> !written.contains("close"));
        doAnswer(i -> {
            String payload = ((WebSocketMessage<?>) i.getArgument(0)).getPayload().toString();
            if (written.isEmpty()) {
                writing.countDown();
                release.await(SEND_TIME_LIMIT_MS, TimeUnit.MILLISECONDS);
            }
            written.add(payload);
            return null;
        }).when(socket).sendMessage(any());
        doAnswer(i -> written.add("close")).when(socket).close(any());
        return socket;
    }

    @Test
    void anErrorBufferedBehindAnotherWriteIsSentBeforeTheClose() throws Exception {
        FlushBeforeClose.Session session = new FlushBeforeClose.Session(socket(), SEND_TIME_LIMIT_MS, BUFFER_LIMIT);
        Thread connected = new Thread(() -> send(session, "CONNECTED"));
        connected.start();
        assertThat(writing.await(SEND_TIME_LIMIT_MS, TimeUnit.MILLISECONDS)).isTrue();

        // As Spring does after a refused frame: send ERROR, then close at once.
        session.sendMessage(new TextMessage("ERROR"));
        Thread closing = new Thread(() -> close(session));
        closing.start();
        // Gives the close time to happen while CONNECTED is still being written (it waits for it here).
        closing.join(CLOSE_FIRST_MS);
        release.countDown();
        connected.join(SEND_TIME_LIMIT_MS);
        closing.join(SEND_TIME_LIMIT_MS);

        assertThat(written).startsWith("CONNECTED", "ERROR").endsWith("close");
    }

    @Test
    void anErrorLeftInTheBufferAfterTheWriterFinishedIsSentBeforeTheClose() throws Exception {
        FlushBeforeClose.Session session = new FlushBeforeClose.Session(socket(), SEND_TIME_LIMIT_MS, BUFFER_LIMIT);
        release.countDown();
        session.sendMessage(new TextMessage("CONNECTED"));

        session.close(CloseStatus.PROTOCOL_ERROR);

        assertThat(written).containsExactly("CONNECTED", "close");
    }

    private static void send(WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void close(WebSocketSession session) {
        try {
            session.close(CloseStatus.PROTOCOL_ERROR);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
