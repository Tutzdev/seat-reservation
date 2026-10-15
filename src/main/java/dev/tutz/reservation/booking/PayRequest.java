package dev.tutz.reservation.booking;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** @param customerAccountId the customer's wallet-ledger account the tickets are charged to */
public record PayRequest(@NotNull UUID customerAccountId) {
}
