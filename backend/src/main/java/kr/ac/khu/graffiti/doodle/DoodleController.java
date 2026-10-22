package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import kr.ac.khu.graffiti.auth.Access;
import kr.ac.khu.graffiti.auth.AuthUser;
import kr.ac.khu.graffiti.auth.CurrentUser;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import kr.ac.khu.graffiti.doodle.DoodleValidator.InvalidDoodleException;
import kr.ac.khu.graffiti.doodle.DoodleValidator.Validated;
import kr.ac.khu.graffiti.page.PageReader;
import kr.ac.khu.graffiti.page.PageSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/pages/{pageId}/doodles} (API.md §3): ✓ publishes a draft. */
@RestController
class DoodleController {

    // Strict on purpose (SEC-02): unknown or repeated fields, numbers for text, text or fractions for
    // numbers are all refused rather than guessed.
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, c -> c
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    /** The request body; {@code payload} is read once {@code kind} is known. */
    record PublishRequest(String kind, JsonNode payload, DoodleValidator.Size bbox) {
    }

    record Response(ChunkDoodleController.Doodle doodle, Instant cooldownEndsAt) {
    }

    private final PageReader pages;
    private final PublishService publisher;
    private final GraffitiProperties props;

    DoodleController(PageReader pages, PublishService publisher, GraffitiProperties props) {
        this.pages = pages;
        this.publisher = publisher;
        this.props = props;
    }

    @PostMapping("/api/pages/{pageId}/doodles")
    @ResponseStatus(HttpStatus.CREATED)
    Response publish(@PathVariable long pageId, @CurrentUser(Access.AUTHOR) AuthUser user,
            HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        PageSnapshot page = pages.requireCurrent(pageId);
        Validated doodle = validate(body, limits(page));
        PublishService.Result result = publisher.publish(pageId, user.id(), doodle);
        DoodleValidator.Box b = doodle.bbox();
        return new Response(new ChunkDoodleController.Doodle(Long.toString(result.id()), doodle.kind(),
                new ChunkDoodleController.Bbox(b.x(), b.y(), b.w(), b.h()), result.payload()),
                result.cooldownEndsAt());
    }

    // Reads at most max-payload-bytes; one byte more means the body is too large (SEC-08, D-11).
    private byte[] readBody(HttpServletRequest request) throws IOException {
        int max = props.doodle().maxPayloadBytes();
        if (request.getContentLengthLong() > max) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(max + 1);
            if (body.length > max) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
            }
            return body;
        }
    }

    private static Validated validate(byte[] body, DoodleValidator.Limits limits) {
        try {
            PublishRequest request = STRICT.readValue(body, PublishRequest.class);
            if (request.payload() == null || !request.payload().isObject()) {
                throw invalid("payload", "required");
            }
            if ("DRAWING".equals(request.kind())) {
                return DoodleValidator.drawing(
                        STRICT.treeToValue(request.payload(), DoodleValidator.DrawingPayload.class),
                        request.bbox(), limits);
            }
            if ("TEXT".equals(request.kind())) {
                return DoodleValidator.text(STRICT.treeToValue(request.payload(), DoodleValidator.TextPayload.class),
                        request.bbox(), limits);
            }
            throw invalid("kind", "not_allowed");
        } catch (InvalidDoodleException e) {
            throw invalid(e.field(), e.reason());
        } catch (UnrecognizedPropertyException e) {
            throw invalid(e.getPropertyName(), "unknown");
        } catch (JsonProcessingException e) {
            throw invalid("body", "malformed");
        } catch (IOException e) {
            throw invalid("body", "malformed");
        }
    }

    private DoodleValidator.Limits limits(PageSnapshot page) {
        GraffitiProperties.Doodle d = props.doodle();
        return new DoodleValidator.Limits(page.geometry(), page.chunkSize(), d.palette().size(), d.strokeWidths(),
                d.fontSizes(), d.maxBbox(), d.textMaxLength());
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", field, "reason", reason));
    }
}
