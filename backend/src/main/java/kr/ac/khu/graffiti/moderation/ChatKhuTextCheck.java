package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * The LLM text check (D-19 ②, specs/07-moderation.md §5.4): asks ChatKHU for a FILTER.md verdict on one text.
 * The text goes between delimiters it cannot contain, and the answer is parsed strictly (SEC-05): anything
 * outside the schema, a non-200 status or a timeout is {@link CheckOutcome#ERROR}. No retry.
 *
 * <p>Exists only while {@code graffiti.moderation.chatkhu.api-key} is set; otherwise {@code chatkhu} stays a stub.
 * Logs the failure kind only: never the text, the answer or its {@code reason} (SEC-13).
 */
@Component
@Conditional(ChatKhuTextCheck.KeyPresent.class)
class ChatKhuTextCheck implements ModerationCheck {

    static final String NAME = "chatkhu";
    static final String OPEN = "<<<TEXT";
    static final String CLOSE = "TEXT>>>";

    private static final Logger log = LoggerFactory.getLogger(ChatKhuTextCheck.class);
    private static final Set<String> VERDICTS = Set.of("PASS", "BLOCK", "REVIEW");
    private static final Set<String> CATEGORIES = Set.of(CheckResult.PROFANITY, "HATE", "SEXUAL", "PERSONAL_INFO");
    private static final Set<String> FIELDS = Set.of("verdict", "category", "reason");
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final String apiKey;
    private final ObjectMapper json;
    private final String prompt;
    private final HttpClient http;

    @Autowired
    ChatKhuTextCheck(GraffitiProperties props, ObjectMapper json) {
        this(URI.create(props.moderation().chatkhu().baseUrl()), props.moderation().chatkhu().model(),
                props.moderation().chatkhu().timeout(), props.moderation().chatkhu().apiKey(), json);
    }

    ChatKhuTextCheck(URI baseUrl, String model, Duration timeout, String apiKey, ObjectMapper json) {
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions/");
        this.model = model;
        this.timeout = timeout;
        this.apiKey = apiKey;
        this.json = json;
        this.prompt = read("moderation/chatkhu-prompt.txt");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        if (!input.isText()) {
            return CheckResult.skipped(NAME);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body(input.text()), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("ChatKHU call timed out");
            return CheckResult.error(NAME, "TIMEOUT");
        } catch (IOException e) {
            log.warn("ChatKHU call failed: {}", e.getClass().getSimpleName());
            return CheckResult.error(NAME, "IO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.error(NAME, "INTERRUPTED");
        }
        if (response.statusCode() != 200) {
            log.warn("ChatKHU call answered {}", response.statusCode());
            return CheckResult.error(NAME, "HTTP_" + response.statusCode());
        }
        CheckResult result = parse(response.body());
        if (result == null) {
            log.warn("ChatKHU answered outside the schema");
            return CheckResult.error(NAME, "BAD_RESPONSE");
        }
        return result;
    }

    /** The text with every delimiter taken out, so it cannot close its own block (SEC-05). */
    static String strip(String text) {
        String s = text;
        String before;
        do {
            before = s;
            s = s.replace(OPEN, "").replace(CLOSE, "");
        } while (!s.equals(before));
        return s;
    }

    private String body(String text) {
        ObjectNode root = json.createObjectNode().put("model", model);
        root.putObject("response_format").put("type", "json_object");
        root.putArray("messages")
                .add(json.createObjectNode().put("role", "system").put("content", prompt))
                .add(json.createObjectNode().put("role", "user")
                        .put("content", OPEN + "\n" + strip(text) + "\n" + CLOSE));
        return root.toString();
    }

    // Null when the completion or its message is not exactly the §5.4 schema.
    private CheckResult parse(byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            return null;
        }
        JsonNode answer;
        try {
            JsonNode content = json.readTree(body).path("choices").path(0).path("message").get("content");
            if (content == null || !content.isTextual()) {
                return null;
            }
            answer = json.readTree(content.asText());
        } catch (IOException e) {
            return null;
        }
        if (answer == null || !answer.isObject()) {
            return null;
        }
        for (String field : (Iterable<String>) answer::fieldNames) {
            if (!FIELDS.contains(field)) {
                return null;
            }
        }
        JsonNode verdict = answer.get("verdict");
        JsonNode category = answer.get("category");
        JsonNode reason = answer.get("reason");
        if (verdict == null || !verdict.isTextual() || !VERDICTS.contains(verdict.asText())
                || (reason != null && !reason.isTextual())) {
            return null;
        }
        boolean noCategory = category == null || category.isNull();
        if (!noCategory && (!category.isTextual() || !CATEGORIES.contains(category.asText()))) {
            return null;
        }
        return switch (verdict.asText()) {
            case "PASS" -> noCategory ? CheckResult.pass(NAME) : null;
            case "BLOCK" -> noCategory ? null : CheckResult.block(NAME, category.asText());
            default -> noCategory ? null : CheckResult.review(NAME, category.asText());
        };
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing " + path, e);
        }
    }

    /** True when the API key is set and not blank. */
    static class KeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("graffiti.moderation.chatkhu.api-key");
            return key != null && !key.isBlank();
        }
    }
}
