package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
            case CAPTURED, REFUNDED -> Optional.empty();
            case REFUND_PENDING -> Optional.of(payment); // the caller pushes the refund once more
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

    /**
     * The money moved: sell the seats and confirm the booking, if the booking still can be confirmed.
     *
     * <p>The ledger call takes time, and the world moves meanwhile. The hold may have passed its deadline and
     * some seat may have gone to another customer, or the customer may have cancelled. Then the booking cannot
     * be honoured and the charge is marked for a refund, in the same transaction that found this out.
     *
     * @return the payment to refund, or empty if the booking is confirmed
     */
    @Transactional
    Optional<Payment> capture(UUID bookingId, UUID transferId) {
        Instant now = clock.instant();
        Booking booking = bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        Payment payment = payments.findById(bookingId).orElseThrow();
        if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.DECLINED) {
            // A concurrent request for the same booking settled it first.
            return payment.getStatus() == PaymentStatus.REFUND_PENDING ? Optional.of(payment) : Optional.empty();
        }

        // The stored status, not the deadline: a late payment still wins if nobody took the seats.
        if (booking.getStatus() == BookingStatus.HELD) {
            int sold = seats.sell(bookingId);
            if (sold == booking.getSeats().size()) {
                booking.confirm(now);
                payment.capture(transferId, now);
                return Optional.empty();
            }
            // Another booking took a seat after the deadline. Selling only some seats is worse than none:
            // put the ones this booking still had back on sale.
            seats.releaseAll(List.of(bookingId));
            booking.expire(now);
        }
        payment.captureForRefund(transferId, now);
        return Optional.of(payment);
    }

    @Transactional
    void refunded(UUID bookingId, UUID refundTransferId) {
        bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        Payment payment = payments.findById(bookingId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            payment.refunded(refundTransferId, clock.instant());
        }
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
