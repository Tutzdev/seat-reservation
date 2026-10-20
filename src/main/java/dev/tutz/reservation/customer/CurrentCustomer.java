package dev.tutz.reservation.customer;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The customer behind a request is the subject of its access token. */
public final class CurrentCustomer {

    private CurrentCustomer() {
    }

    public static UUID idOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
