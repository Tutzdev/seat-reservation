package dev.tutz.reservation.common;

import java.time.Duration;

/** A client went over a rate limit. Answered with 429 and Retry-After. */
public class TooManyRequestsException extends RuntimeException {

    private final String code;
    private final Duration retryAfter;

    public TooManyRequestsException(String code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String getCode() {
        return code;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
