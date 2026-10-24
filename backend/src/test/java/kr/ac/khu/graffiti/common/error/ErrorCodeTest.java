package kr.ac.khu.graffiti.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    // Mirrors the table in docs/API.md §0. Update both together.
    private static final Map<String, Integer> API_DOC = Map.ofEntries(
            Map.entry("UNAUTHENTICATED", 401),
            Map.entry("CONSENT_REQUIRED", 403),
            Map.entry("FORBIDDEN", 403),
            Map.entry("BANNED", 403),
            Map.entry("CSRF_INVALID", 403),
            Map.entry("DOMAIN_NOT_ALLOWED", 403),
            Map.entry("NOT_FOUND", 404),
            Map.entry("PAGE_ENDED", 409),
            Map.entry("STATE_CONFLICT", 409),
            Map.entry("VALIDATION_FAILED", 400),
            Map.entry("PAYLOAD_TOO_LARGE", 413),
            Map.entry("MODERATION_REJECTED", 422),
            Map.entry("COOLDOWN", 429),
            Map.entry("RATE_LIMITED", 429),
            Map.entry("INTERNAL", 500));

    @Test
    void matchesApiDocumentTable() {
        Map<String, Integer> actual = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, c -> c.status().value()));
        assertThat(actual).isEqualTo(API_DOC);
    }

    @Test
    void everyCodeHasKoreanDefaultMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.defaultMessage()).as(code.name()).isNotBlank().matches(".*[가-힣].*");
        }
    }
}
