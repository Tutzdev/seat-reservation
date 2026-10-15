package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private final SeatHolder holder;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final PaymentRepository payments;
    private final Clock clock;

    public BookingService(SeatHolder holder, BookingRepository bookings, SeatRepository seats,
            PaymentRepository payments, Clock clock) {
        this.holder = holder;
        this.bookings = bookings;
        this.seats = seats;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Makes holds safe to retry: the same Idempotency-Key returns the booking that already exists instead
     * of creating a second one. Only successful holds are remembered; a request that lost its seats rolled
     * back and can be retried with the same key.
     */
    public HoldResult hold(HoldRequest request, String idempotencyKey) {
        HoldCommand command = HoldCommand.of(request, idempotencyKey);
        Optional<HoldResult> replay = replayOf(command);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return HoldResult.created(holder.hold(command));
        } catch (DataIntegrityViolationException exception) {
            // Lost the race against a request with the same key that committed first.
            return replayOf(command).orElseThrow(() -> exception);
        }
    }

    @Transactional
    public BookingResponse cancel(UUID bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        if (booking.cancel(now)) {
            seats.releaseAll(List.of(bookingId));
        }
        return respond(booking, now);
    }

    @Transactional(readOnly = true)
    public BookingResponse find(UUID bookingId) {
        Instant now = clock.instant();
        return bookings.findById(bookingId)
                .map(booking -> respond(booking, now))
                .orElseThrow(() -> notFound(bookingId));
    }

    private Optional<HoldResult> replayOf(HoldCommand command) {
        return bookings.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> {
                    if (!existing.wasRequestedWith(command.fingerprint())) {
                        throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different request.");
                    }
                    return HoldResult.replayed(respond(existing, clock.instant()));
                });
    }

    private BookingResponse respond(Booking booking, Instant now) {
        return BookingResponse.from(booking, payments.findById(booking.getId()).orElse(null), now);
    }

    private static ResourceNotFoundException notFound(UUID bookingId) {
        return new ResourceNotFoundException("Booking %s not found.".formatted(bookingId));
    }
}
