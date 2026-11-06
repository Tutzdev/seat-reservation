package dev.tutz.reservation.common;

/** A dependency the request needs cannot be reached. Answered with 503 and Retry-After. */
public class UnavailableException extends RuntimeException {

    private final String code;

    public UnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
