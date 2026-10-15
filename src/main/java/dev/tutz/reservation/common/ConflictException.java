package dev.tutz.reservation.common;

import java.util.Map;
import java.util.Set;

/**
 * The request is valid but lost against the current state, for example a seat someone else already holds.
 * Answered with 409; {@code details} become extra Problem Details members the client can act on.
 */
public class ConflictException extends RuntimeException {

    /** Members every error response already has. A detail with one of these names would overwrite it. */
    private static final Set<String> RESERVED_MEMBERS = Set.of("type", "title", "status", "detail", "instance",
            "code", "timestamp");

    private final String code;
    private final Map<String, Object> details;

    public ConflictException(String code, String message, Map<String, Object> details) {
        super(message);
        details.keySet().stream().filter(RESERVED_MEMBERS::contains).findAny().ifPresent(member -> {
            throw new IllegalArgumentException("'%s' is a standard Problem Details member.".formatted(member));
        });
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
