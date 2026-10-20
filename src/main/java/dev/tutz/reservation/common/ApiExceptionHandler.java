package dev.tutz.reservation.common;

import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error leaves the API as RFC 9457 Problem Details with a stable {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemResponses problems;

    public ApiExceptionHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.UNPROCESSABLE_CONTENT, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException exception, HttpServletRequest request) {
        ProblemDetail response = problems.create(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), request);
        exception.getDetails().forEach(response::setProperty);

        return response;
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    ProblemDetail handlePaymentDeclined(PaymentDeclinedException exception, HttpServletRequest request) {
        ProblemDetail response = problems.create(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_DECLINED", exception.getMessage(), request);
        response.setProperty("reason", exception.getReason());
        return response;
    }

    /**
     * The outcome is unknown, not failed: the client is told to repeat the same call, which settles the payment
     * without charging twice. Retry-After keeps a crowd of clients from hammering a ledger that is struggling.
     */
    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ProblemDetail> handlePaymentUnavailable(PaymentUnavailableException exception, HttpServletRequest request) {
        log.warn("Payment outcome unknown on {}: {}", request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problems.create(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE",
                        "The payment could not be confirmed yet. Repeat the request; it will not charge twice.", request));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    /** A missing, malformed or expired token, raised by the security filter before any controller runs. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated(AuthenticationException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "A valid access token is required. Get one from POST /api/v1/auth/token.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleForbidden(AccessDeniedException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your account cannot do this.", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationFailed(errors, request);
    }

    /**
     * When a handler also validates a header or path variable, Spring validates the whole method at once
     * and the body errors arrive here instead of as {@link MethodArgumentNotValidException}.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(error -> errors.add(new FieldError(error.getField(), error.getDefaultMessage())));
            } else {
                String parameter = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(error -> errors.add(new FieldError(parameter, error.getDefaultMessage())));
            }
        }
        return validationFailed(errors, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        String detail = "Header %s is required.".formatted(exception.getHeaderName());
        return problems.create(HttpStatus.BAD_REQUEST, "MISSING_HEADER", detail, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleInvalidInput(Exception exception, HttpServletRequest request) {
        return problems.create(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request parameters.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrityViolation(DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Persistence conflict on {}", request.getRequestURI());
        return problems.create(HttpStatus.CONFLICT, "CONFLICT",
                "The request conflicts with the current state of the resource.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse response) {
            HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
            return ResponseEntity.status(status).headers(response.getHeaders())
                    .body(problems.create(status, status.name(), status.getReasonPhrase(), request));
        }
        // The client only gets a generic message: exception text may carry SQL or internal details.
        log.error("Unexpected failure on {}", request.getRequestURI(), exception);
        return ResponseEntity.internalServerError()
                .body(problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.", request));
    }

    private ProblemDetail validationFailed(List<FieldError> errors, HttpServletRequest request) {
        ProblemDetail response = problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request.", request);
        response.setProperty("errors", errors.stream().distinct().toList());
        return response;
    }

    /** Headers are reported by their HTTP name (Idempotency-Key), not by the Java parameter name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header == null) {
            return parameter.getParameterName();
        }
        return header.name().isEmpty() ? header.value() : header.name();
    }

    record FieldError(String field, String message) {
    }
}
