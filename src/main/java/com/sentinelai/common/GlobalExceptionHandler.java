package com.sentinelai.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates domain and framework exceptions into {@link ApiError}. Anything not
 * listed here is a bug: it is logged in full and reported as a generic 500 so
 * internal details never leak to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, Map.of());
    }

    /**
     * A URL that matched no handler.
     *
     * <p>Without this, a mistyped path reaches the static-resource handler, which
     * throws {@link NoResourceFoundException}; nothing claims it, so it surfaces as a
     * 500 with a stack trace in the log. That is wrong twice over. The request did not
     * fail, it asked for something that does not exist — which is a 404. And a
     * "server error" for a bad URL sends whoever is debugging it into the application
     * code instead of at the typo that caused it.
     *
     * <p>Logged at debug rather than error, since an unmatched path is usually a
     * scanner or a mistake, not a fault worth waking anyone for.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                      HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint " + request.getRequestURI(),
                request, Map.of());
    }

    @ExceptionHandler(ForbiddenActionException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenActionException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(PayloadRejectedException.class)
    public ResponseEntity<ApiError> handleRejected(PayloadRejectedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> handleTransition(InvalidStateTransitionException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", ex.getMessage(), request,
                Map.of("from", ex.getFrom(), "to", ex.getTo()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request, Map.of());
    }

    /**
     * Two engineers edited the same incident concurrently. This is expected
     * traffic, not an outage, so it is surfaced as a 409 that tells the client
     * to re-read and retry.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> handleConcurrency(Exception ex, HttpServletRequest request) {
        log.info("Concurrent write rejected for {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The incident was modified concurrently. Re-read it and retry.", request, Map.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        if (ex instanceof MethodArgumentNotValidException invalid) {
            for (FieldError fieldError : invalid.getBindingResult().getFieldErrors()) {
                details.putIfAbsent(fieldError.getField(),
                        fieldError.getDefaultMessage() == null ? "is invalid" : fieldError.getDefaultMessage());
            }
            invalid.getBindingResult().getGlobalErrors()
                    .forEach(e -> details.putIfAbsent(e.getObjectName(),
                            e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()));
        } else {
            // A service that rejects caller input with IllegalArgumentException has
            // found a bad request. Letting it reach the catch-all would report a
            // client mistake as a 500 and send the caller looking at our logs.
            details.put("reason", ex.getMessage() == null ? "is invalid" : ex.getMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, details);
    }

    /**
     * A body Jackson could not read.
     *
     * <p>Two different mistakes arrive here and deserve different answers. Bytes that
     * are not JSON at all are genuinely malformed. A well-formed body whose field
     * holds a value Jackson cannot convert — a severity of "WARN", an instant that is
     * not one — is a validation failure, and saying "Malformed or unparseable request
     * body" about it sends the caller hunting for escaping and braces instead of at
     * the one field that is wrong.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         HttpServletRequest request) {
        Map<String, String> details = fieldConversionFailure(ex);
        if (!details.isEmpty()) {
            return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                    request, details);
        }
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed or unparseable request body",
                request, Map.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ex.getMessage(), request, Map.of());
    }

    /**
     * Extracts the offending field from a Jackson conversion failure.
     *
     * <p>{@link InvalidFormatException} covers enum and scalar mismatches;
     * {@link MismatchedInputException} covers the wider family. Anything else is a
     * structural parse problem, and the caller is told the body is unreadable.
     *
     * @return field name to message, or empty when this is not a field-level failure
     */
    private Map<String, String> fieldConversionFailure(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        String field;
        String expected;
        Object actual = null;

        if (cause instanceof InvalidFormatException invalid) {
            field = lastFieldName(invalid.getPath());
            expected = describeExpected(invalid.getTargetType());
            actual = invalid.getValue();
        } else if (cause instanceof MismatchedInputException mismatched
                && mismatched.getPath() != null && !mismatched.getPath().isEmpty()) {
            field = lastFieldName(mismatched.getPath());
            expected = describeExpected(mismatched.getTargetType());
        } else {
            return Map.of();
        }

        if (field == null) {
            return Map.of();
        }
        String message = actual == null
                ? "is not a valid " + expected
                : "'" + truncate(String.valueOf(actual)) + "' is not a valid " + expected;
        return Map.of(field, message);
    }

    private String lastFieldName(List<JsonMappingException.Reference> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.get(path.size() - 1).getFieldName();
    }

    /**
     * Describes what the field should have held.
     *
     * <p>An enum reports the accepted constants rather than the Java type name:
     * "is not a valid severity" leaves the caller guessing, while naming the five legal
     * values tells them exactly what to send instead. The type name is turned into
     * words ({@code IncidentStatus} becomes "incident status") because this text is
     * read by a producer author, not by a Java compiler.
     */
    private String describeExpected(Class<?> type) {
        if (type == null) {
            return "value";
        }
        if (!type.isEnum()) {
            return type.getSimpleName();
        }
        List<String> names = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            names.add(String.valueOf(constant));
        }
        return words(type.getSimpleName()) + " (expected one of " + String.join(", ", names) + ")";
    }

    /** {@code IncidentStatus} to "incident status". */
    private String words(String camelCase) {
        StringBuilder out = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char current = camelCase.charAt(i);
            // Break before a capital that follows a lowercase letter, so acronyms stay
            // together: "HTTPStatus" becomes "http status", not "h t t p status".
            if (i > 0 && Character.isUpperCase(current) && !Character.isUpperCase(camelCase.charAt(i - 1))) {
                out.append(' ');
            }
            out.append(Character.toLowerCase(current));
        }
        return out.toString();
    }

    /** Truncated: this text came from a client and is echoed back, not trusted. */
    private String truncate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 59) + "…";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this action", request, Map.of());
    }

    /**
     * Bad credentials on the login endpoint get their own message; every other
     * authentication failure means "you did not authenticate at all".
     *
     * <p>The two are different situations and the default message conflates them. A
     * caller who submitted a wrong password was sent "Authentication required", which
     * reads as though the request lacked a token — so they would go looking for a missing
     * header while their password was simply wrong. Only {@code BadCredentialsException}
     * is special-cased rather than echoing any authentication exception's message, since
     * other implementations carry internal detail.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                ex.getMessage() == null ? "Invalid email or password" : ex.getMessage(),
                request, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request, Map.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest request, Map<String, String> details) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                TraceContext.currentTraceId(),
                details.isEmpty() ? null : details);
        return ResponseEntity.status(status).body(body);
    }
}
