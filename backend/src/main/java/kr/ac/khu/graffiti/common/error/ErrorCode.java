package kr.ac.khu.graffiti.common.error;

import org.springframework.http.HttpStatus;

/** Error codes from docs/API.md §0. The message is shown to users, so it is Korean. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "개인정보 수집·이용 동의가 필요해요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요."),
    BANNED(HttpStatus.FORBIDDEN, "이용이 제한된 계정이에요."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청이 만료됐어요. 페이지를 새로고침해 주세요."),
    DOMAIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "경희대학교 계정으로만 로그인할 수 있어요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
    PAGE_ENDED(HttpStatus.CONFLICT, "이 페이지는 이미 넘어갔어요."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않아요."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "낙서가 너무 커요."),
    MODERATION_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "부적절한 내용이 감지되어 게시할 수 없어요."),
    COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "아직 다음 낙서를 게시할 수 없어요."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했어요.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
