package dev.tutz.reservation.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param login failed logins allowed per e-mail */
@Validated
@ConfigurationProperties("reservation.rate-limits")
public record RateLimitProperties(@Valid @NotNull Limit login) {

    /** {@code requests} at once, then one more every {@code per / requests}. */
    public record Limit(@Positive int requests, @NotNull Duration per) {

        Duration interval() {
            return per.dividedBy(requests);
        }
    }
}
