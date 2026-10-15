package dev.tutz.reservation.booking;

import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentGateway;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Pays for a hold through the ledger. The flow is record, call, record: the intent is committed before
 * the HTTP call and the outcome after it, so a crash or timeout in between leaves a PENDING payment that
 * says exactly what to retry.
 */
@Service
public class PaymentService {

    private final PaymentRecorder recorder;
    private final PaymentGateway gateway;
    private final BookingService bookings;

    public PaymentService(PaymentRecorder recorder, PaymentGateway gateway, BookingService bookings) {
        this.recorder = recorder;
        this.gateway = gateway;
        this.bookings = bookings;
    }

    /**
     * Safe to call again for the same booking: a settled payment is returned as it is, and a pending one
     * repeats the ledger call with the same key, which never charges twice.
     *
     * @throws PaymentDeclinedException if the ledger refused the charge; the hold is kept
     * @throws PaymentUnavailableException if the outcome is unknown; calling again resolves it
     */
    public BookingResponse pay(UUID bookingId, PayRequest request) {
        recorder.start(bookingId, request.customerAccountId()).ifPresent(this::charge);
        return bookings.find(bookingId);
    }

    private void charge(Payment payment) {
        UUID transferId;
        try {
            transferId = gateway.charge(payment.idempotencyKey(), payment.getCustomerAccountId(),
                    payment.getAmountCents(), "Booking " + payment.getBookingId());
        } catch (PaymentDeclinedException declined) {
            recorder.decline(payment.getBookingId(), declined.getReason());
            throw declined;
        }
        recorder.capture(payment.getBookingId(), transferId);
    }
}
