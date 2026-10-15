package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions before and after the ledger call. Each one locks the booking row, so a payment,
 * a cancel and the expiry sweeper never change the same booking at once. No lock is held while the
 * ledger is being called: a slow ledger must not freeze bookings.
 */
@Component
class PaymentRecorder {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final SeatRepository seats;
    private final Clock clock;

    PaymentRecorder(BookingRepository bookings, PaymentRepository payments, SeatRepository seats, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.seats = seats;
        this.clock = clock;
    }

    /**
     * Writes down the intent to charge before the ledger is called.
     *
     * @return the payment to charge, or empty when there is nothing left to charge: the booking was free
     *         (and is confirmed now) or an earlier request already settled it
     */
    @Transactional
    Optional<Payment> start(UUID bookingId, UUID customerAccountId) {
        Instant now = clock.instant();
        Booking booking = bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        Optional<Payment> existing = payments.findById(bookingId);
        if (existing.isPresent()) {
            return resume(existing.get(), booking, customerAccountId, now);
        }

        requireActive(booking, now);
        if (booking.getTotalCents() == 0) {
            seats.sell(bookingId);
            booking.confirm(now);
            return Optional.empty();
        }
        return Optional.of(payments.save(Payment.start(booking, customerAccountId, now)));
    }

    private Optional<Payment> resume(Payment payment, Booking booking, UUID customerAccountId, Instant now) {
        return switch (payment.getStatus()) {
            case CAPTURED -> Optional.empty();
            case PENDING -> {
                // Same account: a retry after a timeout, safe because the ledger call is idempotent.
                // Another account would reuse the ledger key for a different charge.
                if (!payment.getCustomerAccountId().equals(customerAccountId)) {
                    throw new ConflictException("PAYMENT_IN_PROGRESS",
                            "A payment from another account is still being processed for this booking.", Map.of());
                }
                yield Optional.of(payment);
            }
            case DECLINED -> {
                requireActive(booking, now);
                payment.retry(customerAccountId, now);
                yield Optional.of(payment);
            }
        };
    }

    /** The money moved: sell the seats and confirm the booking. */
    @Transactional
    void capture(UUID bookingId, UUID transferId) {
        Instant now = clock.instant();
        Booking booking = bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        Payment payment = payments.findById(bookingId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return; // a concurrent request for the same booking got here first
        }

        int sold = seats.sell(bookingId);
        if (sold != booking.getSeats().size()) {
            throw new IllegalStateException("Booking %s lost seats before its payment was captured.".formatted(bookingId));
        }
        booking.confirm(now);
        payment.capture(transferId, now);
    }

    @Transactional
    void decline(UUID bookingId, String reason) {
        bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        Payment payment = payments.findById(bookingId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.decline(reason, clock.instant());
        }
    }

    private static void requireActive(Booking booking, Instant now) {
        BookingStatus current = booking.statusAt(now);
        if (current != BookingStatus.HELD) {
            throw new ConflictException("BOOKING_NOT_ACTIVE",
                    "Booking is %s and can no longer be paid.".formatted(current), Map.of("status", current));
        }
    }

    private static ResourceNotFoundException notFound(UUID bookingId) {
        return new ResourceNotFoundException("Booking %s not found.".formatted(bookingId));
    }
}
