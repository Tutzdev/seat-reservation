package dev.tutz.reservation.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail response = problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request body.", request);
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .distinct()
                .toList();
        response.setProperty("errors", errors);

        return response;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        String detail = "Header %s is required.".formatted(exception.getHeaderName());
        return problems.create(HttpStatus.BAD_REQUEST, "MISSING_HEADER", detail, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
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

    record FieldError(String field, String message) {
    }
}
