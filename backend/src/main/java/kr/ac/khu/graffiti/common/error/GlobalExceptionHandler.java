package kr.ac.khu.graffiti.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to the {@link ApiError} body. Spring MVC's own exceptions keep their HTTP
 * status; the body never exposes exception messages or stack traces (SEC-13).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.code().status())
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return body(ErrorCode.INTERNAL, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        FieldError field = ex.getBindingResult().getFieldError();
        if (field != null) {
            details.put("field", field.getField());
            details.put("reason", field.getCode());
        }
        return new ResponseEntity<>(apiError(ErrorCode.VALIDATION_FAILED, details), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Framework exception", ex);
        }
        return new ResponseEntity<>(apiError(codeFor(status), null), headers, status);
    }

    // API.md has no dedicated codes for e.g. 405/415, so other client errors report VALIDATION_FAILED
    // with the framework's status.
    private static ErrorCode codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.value() == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            return ErrorCode.PAYLOAD_TOO_LARGE;
        }
        if (status.is4xxClientError()) {
            return ErrorCode.VALIDATION_FAILED;
        }
        return ErrorCode.INTERNAL;
    }

    private static ResponseEntity<ApiError> body(ErrorCode code, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code, code.defaultMessage(), details));
    }

    private static ApiError apiError(ErrorCode code, Map<String, Object> details) {
        return ApiError.of(code, code.defaultMessage(), details);
    }
}
