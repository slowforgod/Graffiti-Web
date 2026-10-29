package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MlModerationProviderTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final String OK = """
            {"modelVersion":"clip-zs-1","image":{"label":"FLAGGED","scores":{"SEXUAL":0.81,"HATE":0.04}},
             "ocr":{"text":"비밀 글자","confidence":0.62},"latencyMs":840}""";

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>(OK);
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/moderate", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
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

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsThePngAsMultipartAndParsesTheAnswer() {
        MlAnswer answer = provider().moderate(PNG, "req-1");

        assertThat(answer.outcome()).isEqualTo(MlAnswer.Outcome.OK);
        assertThat(answer.modelVersion()).isEqualTo("clip-zs-1");
        assertThat(answer.label()).isEqualTo(MlAnswer.Label.FLAGGED);
        assertThat(answer.scores()).containsEntry("SEXUAL", 0.81).containsEntry("HATE", 0.04);
        assertThat(answer.topCategory()).isEqualTo("SEXUAL");
        assertThat(answer.ocrText()).isEqualTo("비밀 글자");
        assertThat(answer.ocrConfidence()).isEqualTo(0.62);
        assertThat(answer.latencyMs()).isEqualTo(840);

        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        String sent = new String(received.get(), StandardCharsets.ISO_8859_1);
        assertThat(sent).contains("name=\"requestId\"\r\n\r\nreq-1\r\n")
                .contains("name=\"image\"; filename=\"doodle.png\"\r\nContent-Type: image/png\r\n\r\n"
                        + new String(PNG, StandardCharsets.ISO_8859_1) + "\r\n");
    }

    @Test
    void aSlowServerIsATimeout() {
        delay.set(TIMEOUT.multipliedBy(3));

        long started = System.nanoTime();
        MlAnswer answer = provider().moderate(PNG, "req-1");

        assertThat(answer.outcome()).isEqualTo(MlAnswer.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(2));
    }

    @Test
    void anErrorStatusOrNoServerIsAnError() {
        status.set(503);
        assertThat(provider().moderate(PNG, "req-1").outcome()).isEqualTo(MlAnswer.Outcome.ERROR);

        int port = server.getAddress().getPort();
        server.stop(0);
        MlModerationProvider gone = new MlModerationProvider(URI.create("http://127.0.0.1:" + port), TIMEOUT,
                new ObjectMapper());
        assertThat(gone.moderate(PNG, "req-1").outcome()).isIn(MlAnswer.Outcome.ERROR, MlAnswer.Outcome.TIMEOUT);
    }

    @Test
    void anAnswerOutsideTheSchemaIsAnErrorAndIsNotLogged(CapturedOutput output) {
        for (String bad : List.of(
                "not json 비밀",
                OK.replace("\"FLAGGED\"", "\"MAYBE\""),
                OK.replace("0.81", "1.5"),
                OK.replace("\"SEXUAL\"", "\"sexual 비밀\""),
                OK.replace("\"modelVersion\":\"clip-zs-1\",", ""),
                OK.replace("\"confidence\":0.62", "\"confidence\":\"high\""),
                OK.replace("840", "-1"),
                OK.replace("{\"SEXUAL\":0.81,\"HATE\":0.04}", "{}"))) {
            body.set(bad);
            assertThat(provider().moderate(PNG, "req-1").outcome()).as(bad).isEqualTo(MlAnswer.Outcome.ERROR);
        }
        assertThat(output.getAll()).doesNotContain("비밀");
    }

    private MlModerationProvider provider() {
        return new MlModerationProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TIMEOUT,
                new ObjectMapper());
    }
}
