package dev.tutz.reservation.customer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Secrets come from the environment. The application refuses to start with a weak JWT secret.
 *
 * @param organizerEmail and {@code organizerPassword}: the first organizer, created on startup when both are set
 */
@Validated
@ConfigurationProperties("reservation.security")
public record SecurityProperties(
        @NotNull @Size(min = 32, message = "JWT_SECRET must have at least 32 characters") String jwtSecret,
        @NotNull Duration tokenTtl,
        String organizerEmail,
        String organizerPassword) {

    static final int MIN_PASSWORD_LENGTH = 12;
}
