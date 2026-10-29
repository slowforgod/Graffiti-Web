package kr.ac.khu.graffiti.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.khu.graffiti.auth.SessionUser;
import kr.ac.khu.graffiti.page.FreshPage;
import kr.ac.khu.graffiti.page.PageSnapshot;
import kr.ac.khu.graffiti.support.MutableClock;
import kr.ac.khu.graffiti.support.WebIntegrationTest;
import kr.ac.khu.graffiti.user.User;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 07b in SHADOW against MySQL, with a fake ML server: a published drawing is analysed after the response and
 * the result lands in {@code ml_prediction}, whatever the ML server says or fails to say (INV-13).
 */
@ExtendWith(OutputCaptureExtension.class)
class MlShadowIT extends WebIntegrationTest {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final Duration ML_TIMEOUT = Duration.ofMillis(800);
    private static final String DRAWING = """
            {"kind": "DRAWING", "payload": {"v": 1, "strokes": [{"c": 3, "w": 8, "p": [470, 10, 490, 12]}]}}""";
    private static final String TEXT = """
            {"kind": "TEXT", "payload": {"v": 1, "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900},
             "bbox": {"w": 48, "h": 24}}""";
    private static final String SECRET = "손글씨비밀";

    private static final HttpServer ML = server();
    private static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<Duration> DELAY = new AtomicReference<>(Duration.ZERO);

    @DynamicPropertySource
    static void mlServer(DynamicPropertyRegistry registry) {
        registry.add("graffiti.moderation.ml.base-url", () -> "http://127.0.0.1:" + ML.getAddress().getPort());
        registry.add("graffiti.moderation.ml.timeout", () -> ML_TIMEOUT.toMillis() + "ms");
        registry.add("graffiti.moderation.ml.mode", () -> "SHADOW");
    }

    @AfterAll
    static void stopServer() {
        ML.stop(0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private PageSnapshot page;

    @BeforeEach
    void reset() {
        page = FreshPage.start(context, clock);
        STATUS.set(200);
        DELAY.set(Duration.ZERO);
        BODY.set(answer("FLAGGED", SECRET));
    }

    @Test
    void aFlaggedDrawingIsPublishedAsBeforeAndTheAnalysisRecorded(CapturedOutput output) throws Exception {
        long id = publish(DRAWING);

        Map<String, Object> row = prediction(id);
        assertThat(row).containsEntry("mode", "SHADOW").containsEntry("outcome", "OK")
                .containsEntry("model_version", "clip-zs-1").containsEntry("image_label", "FLAGGED")
                .containsEntry("ocr_text", SECRET).containsEntry("latency_ms", 840)
                // The text checks are stubs until 07: the handwriting is recorded as not checked.
                .containsEntry("ocr_text_outcome", "SKIPPED");
        assertThat((BigDecimal) row.get("ocr_confidence")).isEqualByComparingTo("0.620");
        assertThat(json.readTree(row.get("image_scores").toString()))
                .isEqualTo(json.readTree("{\"SEXUAL\":0.81,\"HATE\":0.04}"));

        Map<String, Object> doodle = jdbc.queryForMap("SELECT status, moderation_state, moderation_detail "
                + "FROM doodle WHERE id = ?", id);
        assertThat(doodle).containsEntry("status", "PUBLISHED").containsEntry("moderation_state", "UNCHECKED");
        assertThat(json.readTree(doodle.get("moderation_detail").toString()).has("ml")).isFalse();
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void anMlServerThatFailsOrIsSlowLeavesTheDoodleAsItWasAndARowSaysSo() throws Exception {
        STATUS.set(503);
        long failed = publish(DRAWING);
        assertThat(prediction(failed)).containsEntry("outcome", "ERROR").containsEntry("model_version", null)
                .containsEntry("image_label", null).containsEntry("ocr_text_outcome", null);

        DELAY.set(ML_TIMEOUT.multipliedBy(2));
        long slow = publish(DRAWING);
        assertThat(prediction(slow)).containsEntry("outcome", "TIMEOUT");

        assertThat(jdbc.queryForList("SELECT moderation_state FROM doodle WHERE id IN (?, ?)", String.class,
                failed, slow)).containsOnly("UNCHECKED");
    }

    @Test
    void noHandwritingIsNoneAndLongHandwritingIsCutForStorage() throws Exception {
        BODY.set(answer("SAFE", ""));
        assertThat(prediction(publish(DRAWING))).containsEntry("ocr_text_outcome", "NONE")
                .containsEntry("ocr_text", null).containsEntry("image_label", "SAFE");

        BODY.set(answer("SAFE", "가".repeat(MlPredictionRecorder.OCR_TEXT_MAX + 20)));
        assertThat(prediction(publish(DRAWING)).get("ocr_text").toString())
                .hasSize(MlPredictionRecorder.OCR_TEXT_MAX);
    }

    @Test
    void textDoodlesAreNotAnalysedAndRowsHoldNoAuthor() throws Exception {
        long text = publish(TEXT);
        long drawing = publish(DRAWING);
        prediction(drawing);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ml_prediction WHERE doodle_id = ?", Integer.class, text))
                .isZero();
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'ml_prediction'", String.class);
        assertThat(columns).noneMatch(c -> c.toLowerCase().contains("author") || c.toLowerCase().contains("user"));
    }

    private Map<String, Object> prediction(long doodleId) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForList(
                "SELECT * FROM ml_prediction WHERE doodle_id = ?", doodleId), rows -> rows.size() == 1).getFirst();
    }

    // A new author each time, so the cooldown never refuses a post.
    private long publish(String body) throws Exception {
        User user = accounts.loginOrRegister("sub-" + UUID.randomUUID(), "m-" + UUID.randomUUID() + "@khu.ac.kr");
        accounts.consent(user.getId());
        String response = mvc.perform(post("/api/pages/{p}/doodles", page.id())
                        .cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN)
                        .with(authentication(new UsernamePasswordAuthenticationToken(new SessionUser(user.getId()),
                                null, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/doodle/id").asLong();
    }

    private static String answer(String label, String text) {
        return """
                {"modelVersion":"clip-zs-1","image":{"label":"%s","scores":{"SEXUAL":0.81,"HATE":0.04}},
                 "ocr":{"text":"%s","confidence":%s},"latencyMs":840}"""
                .formatted(label, text, text.isEmpty() ? "0" : "0.62");
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v1/moderate", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(DELAY.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(STATUS.get(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
