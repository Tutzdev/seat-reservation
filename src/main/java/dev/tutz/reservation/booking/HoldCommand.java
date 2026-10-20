package dev.tutz.reservation.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** @param customerId who asked for the hold; an Idempotency-Key only means something together with it */
record HoldCommand(UUID customerId, UUID eventId, List<Long> seatIds, String idempotencyKey) {

    HoldCommand {
        seatIds = List.copyOf(seatIds);
    }

    static HoldCommand of(UUID customerId, HoldRequest request, String idempotencyKey) {
        return new HoldCommand(customerId, request.eventId(), request.seatIds(), idempotencyKey);
    }

    /** Seat order does not matter: [2, 1] and [1, 2] are the same request. */
    String fingerprint() {
        String seats = seatIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return HexFormat.of().formatHex(sha256(eventId + "|" + seats));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every JVM", exception);
        }
    }
}
