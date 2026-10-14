package dev.tutz.reservation.common;

import java.util.Map;

/**
 * The request is valid but lost against the current state, for example a seat someone else already holds.
 * Answered with 409; {@code details} become extra Problem Details members the client can act on.
 */
public class ConflictException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public ConflictException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
