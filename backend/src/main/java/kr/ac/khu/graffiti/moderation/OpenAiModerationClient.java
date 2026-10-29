package kr.ac.khu.graffiti.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * Calls OpenAI's {@code POST /moderations} (specs/07-moderation.md §5.5) and maps {@code results[0].categories}
 * to one {@link CheckResult}: sexual and hate reject, harassment is profanity (shrunk, D-43), and the categories
 * FILTER.md has no place for are REVIEW. A timeout, a non-200 status or a body without the fields is ERROR.
 * Logs the failure kind only, never the input.
 *
 * <p>Exists only while {@code graffiti.moderation.openai.api-key} is set; so do the checks using it.
 */
@Component
@Conditional(OpenAiModerationClient.KeyPresent.class)
class OpenAiModerationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModerationClient.class);
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** OpenAI categories that reject, by FILTER.md category, in the order they win. */
    private static final List<Map.Entry<String, List<String>>> BLOCKS = List.of(
            Map.entry("SEXUAL", List.of("sexual", "sexual/minors")),
            Map.entry("HATE", List.of("hate", "hate/threatening")),
            Map.entry(CheckResult.PROFANITY, List.of("harassment")));

    /** OpenAI categories outside FILTER.md: published as UNCHECKED for an admin to look at (D-42). */
    private static final List<Map.Entry<String, List<String>>> REVIEWS = List.of(
            Map.entry("THREAT", List.of("harassment/threatening")),
            Map.entry("VIOLENCE", List.of("violence", "violence/graphic")),
            Map.entry("SELF_HARM", List.of("self-harm", "self-harm/intent", "self-harm/instructions")),
            Map.entry("ILLICIT", List.of("illicit", "illicit/violent")));

    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final String apiKey;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    OpenAiModerationClient(GraffitiProperties props, ObjectMapper json) {
        this(URI.create(props.moderation().openai().baseUrl()), props.moderation().openai().model(),
                props.moderation().openai().timeout(), props.moderation().openai().apiKey(), json);
    }

    OpenAiModerationClient(URI baseUrl, String model, Duration timeout, String apiKey, ObjectMapper json) {
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/moderations");
        this.model = model;
        this.timeout = timeout;
        this.apiKey = apiKey;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    ObjectMapper json() {
        return json;
    }

    /** {@code input} is the request's {@code input}: a string, or an array of content parts for an image. */
    CheckResult moderate(String check, JsonNode input) {
        ObjectNode body = json.createObjectNode().put("model", model);
        body.set("input", input);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("OpenAI {} call timed out", check);
            return CheckResult.error(check, "TIMEOUT");
        } catch (IOException e) {
            log.warn("OpenAI {} call failed: {}", check, e.getClass().getSimpleName());
            return CheckResult.error(check, "IO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.error(check, "INTERRUPTED");
        }
        if (response.statusCode() != 200) {
            log.warn("OpenAI {} call answered {}", check, response.statusCode());
            return CheckResult.error(check, "HTTP_" + response.statusCode());
        }
        JsonNode categories = categories(response.body());
        if (categories == null) {
            log.warn("OpenAI {} answered outside the schema", check);
            return CheckResult.error(check, "BAD_RESPONSE");
        }
        String block = first(categories, BLOCKS);
        if (block != null) {
            return CheckResult.block(check, block);
        }
        String review = first(categories, REVIEWS);
        return review != null ? CheckResult.review(check, review) : CheckResult.pass(check);
    }

    // Null unless results[0].categories is an object of booleans.
    private JsonNode categories(byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            return null;
        }
        JsonNode categories;
        try {
            categories = json.readTree(body).path("results").path(0).get("categories");
        } catch (IOException e) {
            return null;
        }
        if (categories == null || !categories.isObject()) {
            return null;
        }
        for (JsonNode value : categories) {
            if (!value.isBoolean()) {
                return null;
            }
        }
        return categories;
    }

    private static String first(JsonNode categories, List<Map.Entry<String, List<String>>> table) {
        for (Map.Entry<String, List<String>> e : table) {
            if (e.getValue().stream().anyMatch(name -> categories.path(name).asBoolean(false))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** True when the API key is set and not blank. */
    static class KeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("graffiti.moderation.openai.api-key");
            return key != null && !key.isBlank();
        }
    }
}
