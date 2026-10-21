package kr.ac.khu.graffiti.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Response body: {@code {"error": {"code", "message", "details"}}} (docs/API.md §0). */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(String code, String message, Map<String, Object> details) {
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiError(new Body(code.name(), message, details));
    }
}
