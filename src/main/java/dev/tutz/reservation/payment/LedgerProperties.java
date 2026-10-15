package dev.tutz.reservation.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How to reach wallet-ledger. The application refuses to start without them, instead of failing on the
 * first customer who tries to pay.
 *
 * @param email and {@code password}: the ledger operator this service logs in as
 * @param merchantAccountId the ledger account that receives ticket sales
 * @param readTimeout how long a charge may take before its outcome is treated as unknown
 */
@Validated
@ConfigurationProperties("reservation.payments.ledger")
public record LedgerProperties(
        @NotNull URI baseUrl,
        @NotBlank String email,
        @NotBlank String password,
        @NotNull UUID merchantAccountId,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
