package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment of one booking, shared by the booking id. It is written before the ledger is called, so a
 * charge whose answer was lost is never forgotten: a PENDING row means "money may have moved, find out".
 */
@Entity
@Table(name = "payments")
public class Payment extends AssignedIdEntity {

    private static final int MAX_REASON_LENGTH = 40;

    @Id
    private UUID bookingId;

    @Column(nullable = false)
    private UUID customerAccountId;

    @Column(nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    private UUID transferId;

    @Column(length = MAX_REASON_LENGTH)
    private String declineReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    private Payment(Booking booking, UUID customerAccountId, Instant now) {
        this.bookingId = booking.getId();
        this.customerAccountId = customerAccountId;
        this.amountCents = booking.getTotalCents();
        this.status = PaymentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static Payment start(Booking booking, UUID customerAccountId, Instant now) {
        return new Payment(booking, customerAccountId, now);
    }

    /**
     * The key the ledger deduplicates on. It is derived from the booking, so every attempt to pay the same
     * booking, from the client or from a retry after a timeout, can move the money at most once.
     */
    String idempotencyKey() {
        return "booking-" + bookingId;
    }

    /** A declined payment can be tried again, possibly from another account, since nothing was charged. */
    void retry(UUID customerAccountId, Instant now) {
        requireStatus(PaymentStatus.DECLINED);
        this.customerAccountId = customerAccountId;
        this.status = PaymentStatus.PENDING;
        this.declineReason = null;
        this.updatedAt = now;
    }

    void decline(String reason, Instant now) {
        requireStatus(PaymentStatus.PENDING);
        this.status = PaymentStatus.DECLINED;
        this.declineReason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        this.updatedAt = now;
    }

    /**
     * A decline can still be followed by a capture: a request that timed out may have charged the account
     * after another attempt was refused. The money moved, so the transfer is what counts.
     */
    void capture(UUID transferId, Instant now) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.DECLINED) {
            throw new IllegalStateException("Payment of booking %s is already %s.".formatted(bookingId, status));
        }
        this.transferId = transferId;
        this.status = PaymentStatus.CAPTURED;
        this.declineReason = null;
        this.updatedAt = now;
    }

    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Payment of booking %s is %s, not %s.".formatted(bookingId, status, expected));
        }
    }

    @Override
    public UUID getId() {
        return bookingId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getCustomerAccountId() {
        return customerAccountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
