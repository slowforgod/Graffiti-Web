package kr.ac.khu.graffiti.moderation;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** A local HTTP server standing in for an external moderation API: records the request, answers as set. */
final class FakeApiServer implements AutoCloseable {

    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> body = new AtomicReference<>("{}");
    final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> received = new AtomicReference<>();
    private final HttpServer server;

    FakeApiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delay.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    URI url(String basePath) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
