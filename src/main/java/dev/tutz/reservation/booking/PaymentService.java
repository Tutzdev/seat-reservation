package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentGateway;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pays for a hold through the ledger. The flow is record, call, record: the intent is committed before
 * each HTTP call and the outcome after it, so a crash or timeout in between leaves a PENDING or
 * REFUND_PENDING payment that says exactly what to repeat.
 *
 * <p>There is no distributed transaction with the ledger. When the money moved but the booking cannot be
 * honoured anymore, the charge is undone by a compensating step: a refund through the ledger.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRecorder recorder;
    private final PaymentGateway gateway;
    private final BookingService bookings;

    public PaymentService(PaymentRecorder recorder, PaymentGateway gateway, BookingService bookings) {
        this.recorder = recorder;
        this.gateway = gateway;
        this.bookings = bookings;
    }

    /**
     * Safe to call again for the same booking: a settled payment is returned as it is, and an unsettled one
     * repeats the ledger call with the same key, which never moves the money twice.
     *
     * @throws PaymentDeclinedException if the ledger refused the charge; the hold is kept
     * @throws PaymentUnavailableException if the outcome is unknown; calling again resolves it
     * @throws ConflictException {@code PAYMENT_REFUNDED} if the booking ended while the charge was in flight
     */
    public BookingResponse pay(UUID customerId, UUID bookingId, PayRequest request) {
        recorder.start(customerId, bookingId, request.customerAccountId()).ifPresent(this::settle);

        BookingResponse booking = bookings.find(bookingId);
        if (booking.payment() != null && isRefund(booking.payment().status())) {
            throw new ConflictException("PAYMENT_REFUNDED",
                    "The booking ended before the payment arrived, so the charge is being refunded.",
                    Map.of("bookingStatus", booking.status(), "paymentStatus", booking.payment().status()));
        }
        return booking;
    }

    /**
     * Gives a confirmed booking back at the customer's request. The seats go back on sale and the charge is
     * marked for a refund before the ledger is called. If the ledger does not take the refund now, the booking
     * is refunded anyway and the reconciler sends the money back later.
     *
     * @throws ConflictException {@code BOOKING_NOT_CONFIRMED} if the booking was never paid for
     * @throws dev.tutz.reservation.common.BusinessRuleException {@code REFUND_WINDOW_CLOSED} too close to the event
     */
    public BookingResponse refund(UUID customerId, UUID bookingId) {
        recorder.startRefund(customerId, bookingId).ifPresent(this::settle);
        return bookings.find(bookingId);
    }

    /** Takes an unsettled payment one step further: charges a pending one, refunds one that has to go back. */
    void settle(Payment payment) {
        switch (payment.getStatus()) {
            case PENDING -> charge(payment).ifPresent(this::refund);
            case REFUND_PENDING -> refund(payment);
            default -> {
                // DECLINED waits for the customer; CAPTURED and REFUNDED are final.
            }
        }
    }

    private Optional<Payment> charge(Payment payment) {
        UUID transferId;
        try {
            transferId = gateway.charge(payment.idempotencyKey(), payment.getCustomerAccountId(),
                    payment.getAmountCents(), "Booking " + payment.getBookingId());
        } catch (PaymentDeclinedException declined) {
            recorder.decline(payment.getBookingId(), declined.getReason());
            throw declined;
        }
        return recorder.capture(payment.getBookingId(), transferId);
    }

    /**
     * A failed refund is not the customer's problem to retry: it stays REFUND_PENDING and is pushed again
     * later, so the failure is logged instead of thrown.
     */
    private void refund(Payment payment) {
        try {
            UUID refundId = gateway.refund(payment.getTransferId(), payment.refundIdempotencyKey(),
                    "Refund of booking %s.".formatted(payment.getBookingId()));
            recorder.refunded(payment.getBookingId(), refundId);
        } catch (PaymentUnavailableException | PaymentDeclinedException exception) {
            log.warn("Refund of booking {} failed and will be retried: {}", payment.getBookingId(), exception.getMessage());
        }
    }

    private static boolean isRefund(PaymentStatus status) {
        return status == PaymentStatus.REFUND_PENDING || status == PaymentStatus.REFUNDED;
    }
}
