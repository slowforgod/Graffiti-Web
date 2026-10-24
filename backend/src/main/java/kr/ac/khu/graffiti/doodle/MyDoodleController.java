package kr.ac.khu.graffiti.doodle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.khu.graffiti.auth.Access;
import kr.ac.khu.graffiti.auth.AuthUser;
import kr.ac.khu.graffiti.auth.CurrentUser;
import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The author's own doodles (API.md §3, D-13): list them for selection and PNG saving, delete them. */
@RestController
class MyDoodleController {

    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");

    record Bbox(int x, int y, int w, int h) {
    }

    record Mine(String id, Bbox bbox) {
    }

    record MineResponse(String pageId, List<Mine> doodles) {
    }

    record DeleteRequest(List<String> ids) {
    }

    private final MyDoodleService service;
    private final GraffitiProperties props;

    MyDoodleController(MyDoodleService service, GraffitiProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/api/me/doodles")
    MineResponse mine(@CurrentUser(Access.LOGIN) AuthUser user, @RequestParam(required = false) String pageId) {
        long page = parseId(pageId, "pageId");
        List<Mine> doodles = service.mine(user.id(), page).stream()
                .map(d -> new Mine(Long.toString(d.id()), new Bbox(d.x(), d.y(), d.w(), d.h())))
                .toList();
        return new MineResponse(Long.toString(page), doodles);
    }

    @DeleteMapping("/api/doodles/{doodleId}")
    ResponseEntity<Void> delete(@CurrentUser(Access.AUTHOR) AuthUser user, @PathVariable String doodleId) {
        service.delete(user.id(), List.of(parseId(doodleId, "doodleId")));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/doodles/delete")
    ResponseEntity<Void> deleteMany(@CurrentUser(Access.AUTHOR) AuthUser user, HttpServletRequest request)
            throws IOException {
        DeleteRequest body = read(request);
        if (body.ids() == null || body.ids().isEmpty()) {
            throw invalid("ids", "required");
        }
        // The cap counts what was sent, repeats included, like the chunk list (API.md §2).
        if (body.ids().size() > props.query().maxBatchDelete()) {
            throw invalid("ids", "too_many");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String id : body.ids()) {
            ids.add(parseId(id, "ids"));
        }
        service.delete(user.id(), List.copyOf(ids));
        return ResponseEntity.noContent().build();
    }

    private DeleteRequest read(HttpServletRequest request) throws IOException {
        int max = props.doodle().maxPayloadBytes();
        if (request.getContentLengthLong() > max) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(max + 1);
        }
        if (bytes.length > max) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        try {
            DeleteRequest body = DoodleController.STRICT.readValue(bytes, DeleteRequest.class);
            if (body == null) {
                throw invalid("body", "malformed");
            }
            return body;
        } catch (UnrecognizedPropertyException e) {
            throw invalid(e.getPropertyName(), "unknown");
        } catch (JsonProcessingException e) {
            throw invalid("body", "malformed");
        }
    }

    private static long parseId(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw invalid(field, "required");
        }
        if (!ID.matcher(value).matches()) {
            throw invalid(field, "not_a_number");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(field, "not_a_number");
        }
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", field, "reason", reason));
    }
}
