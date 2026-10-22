package kr.ac.khu.graffiti.realtime;

import kr.ac.khu.graffiti.common.error.ApiException;
import kr.ac.khu.graffiti.common.error.ErrorCode;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * A SEND refused by a {@code @MessageMapping} handler (a turned page, a bad coordinate) keeps the
 * connection open: the error goes to this connection's {@code /user/queue/errors} only (API.md §6).
 * Refusals of the interceptor stay ERROR frames.
 */
@ControllerAdvice(assignableTypes = {CursorController.class, DraftController.class})
class RealtimeErrorAdvice {

    @MessageExceptionHandler(ApiException.class)
    @SendToUser(destinations = RealtimeDestinations.ERRORS_QUEUE, broadcast = false)
    RealtimeEvents.ErrorEvent onRefused(ApiException e) {
        return new RealtimeEvents.ErrorEvent(e.code().name());
    }

    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(destinations = RealtimeDestinations.ERRORS_QUEUE, broadcast = false)
    RealtimeEvents.ErrorEvent onUnreadable(MessageConversionException e) {
        return new RealtimeEvents.ErrorEvent(ErrorCode.VALIDATION_FAILED.name());
    }
}
