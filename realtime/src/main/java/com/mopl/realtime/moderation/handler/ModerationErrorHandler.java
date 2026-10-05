package com.mopl.realtime.moderation.handler;

import com.mopl.realtime.moderation.exception.ModerationException;
import java.time.LocalDateTime;
import java.time.Instant;
import com.mopl.realtime.moderation.dto.SanctionLevel;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice
public class ModerationErrorHandler {
    @MessageExceptionHandler(ModerationException.class)
    @SendToUser(value = "/sub/errors", broadcast = false)
    public ErrorResponse handle(ModerationException exception) {
        var restriction = exception.getRestriction();
        return new ErrorResponse(exception.getErrorCode().getCode(), exception.getMessage(), LocalDateTime.now(),
            restriction == null ? null : restriction.level(), restriction == null ? null : restriction.restrictedUntil());
    }
    public record ErrorResponse(String code, String message, LocalDateTime timestamp,
        SanctionLevel restrictionLevel, Instant restrictedUntil) {}
}
