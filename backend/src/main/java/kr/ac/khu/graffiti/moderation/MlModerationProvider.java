package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Calls the ML server's {@code POST /v1/moderate} (API.md §7) with a rendered drawing and parses the answer
 * strictly. A timeout, a non-200 status or a body that does not match the schema is an answer without a
 * verdict ({@link MlAnswer.Outcome#TIMEOUT} or {@link MlAnswer.Outcome#ERROR}), never an exception (INV-13).
 * Logs the failure kind only: never the body, which may carry OCR text (SEC-17).
 */
@Component
public class MlModerationProvider {

    private static final Logger log = LoggerFactory.getLogger(MlModerationProvider.class);
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_CATEGORIES = 16;

    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    MlModerationProvider(GraffitiProperties props, ObjectMapper json) {
        this(URI.create(props.moderation().ml().baseUrl()), props.moderation().ml().timeout(), json);
    }

    MlModerationProvider(URI baseUrl, Duration timeout, ObjectMapper json) {
        this.endpoint = baseUrl.resolve("/v1/moderate");
        this.timeout = timeout;
        this.json = json;
        // The ML server is on the Docker network only (SEC-17): never through an HTTP proxy.
        this.http = HttpClient.newBuilder().connectTimeout(timeout).proxy(HttpClient.Builder.NO_PROXY).build();
    }

    /** {@code requestId} ties the server's log line to this call; it carries no user information. */
    public MlAnswer moderate(byte[] png, String requestId) {
        String boundary = "graffiti-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, png, requestId)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("ML server call {} timed out", requestId);
            return MlAnswer.failed(MlAnswer.Outcome.TIMEOUT);
        } catch (IOException e) {
            log.warn("ML server call {} failed: {}", requestId, e.getClass().getSimpleName());
            return MlAnswer.failed(MlAnswer.Outcome.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MlAnswer.failed(MlAnswer.Outcome.ERROR);
        }
        if (response.statusCode() != 200) {
            log.warn("ML server call {} answered {}", requestId, response.statusCode());
            return MlAnswer.failed(MlAnswer.Outcome.ERROR);
        }
        MlAnswer answer = parse(response.body());
        if (answer == null) {
            log.warn("ML server call {} answered outside the schema", requestId);
            return MlAnswer.failed(MlAnswer.Outcome.ERROR);
        }
        return answer;
    }

    // Null when the body is not exactly the API.md §7 shape with values in range.
    private MlAnswer parse(byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            return null;
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode version = root.get("modelVersion");
        JsonNode image = root.path("image");
        JsonNode label = image.get("label");
        JsonNode scores = image.get("scores");
        JsonNode text = root.path("ocr").get("text");
        JsonNode confidence = root.path("ocr").get("confidence");
        JsonNode latency = root.get("latencyMs");
        if (version == null || !version.isTextual() || version.asText().isBlank()
                || version.asText().length() > MAX_VERSION_LENGTH
                || label == null || !label.isTextual()
                || scores == null || !scores.isObject() || scores.size() > MAX_CATEGORIES
                || text == null || !text.isTextual()
                || !unit(confidence)
                || latency == null || !latency.canConvertToInt() || !latency.isIntegralNumber() || latency.asInt() < 0) {
            return null;
        }
        MlAnswer.Label parsedLabel;
        try {
            parsedLabel = MlAnswer.Label.valueOf(label.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Double> parsedScores = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : scores.properties()) {
            if (!CATEGORY.matcher(e.getKey()).matches() || !unit(e.getValue())) {
                return null;
            }
            parsedScores.put(e.getKey(), e.getValue().asDouble());
        }
        if (parsedLabel == MlAnswer.Label.FLAGGED && parsedScores.isEmpty()) {
            return null;
        }
        return new MlAnswer(MlAnswer.Outcome.OK, version.asText(), parsedLabel, Map.copyOf(parsedScores),
                text.asText(), confidence.asDouble(), latency.asInt());
    }

    private static boolean unit(JsonNode n) {
        return n != null && n.isNumber() && n.asDouble() >= 0 && n.asDouble() <= 1;
    }

    private static byte[] multipart(String boundary, byte[] png, String requestId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"requestId\"\r\n\r\n" + requestId + "\r\n");
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"doodle.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n");
        out.writeBytes(png);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
