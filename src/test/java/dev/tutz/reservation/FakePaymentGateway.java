package dev.tutz.reservation;

import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentGateway;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stands in for wallet-ledger with the one property the payment flow relies on: the same Idempotency-Key
 * moves the money once, however many times it is sent. Tests script the next answers to reproduce
 * declines, outages and the worst case, a charge that went through but whose answer was lost.
 */
public class FakePaymentGateway implements PaymentGateway {

    public enum Answer { APPROVE, DECLINE, UNAVAILABLE, TIME_OUT_AFTER_CHARGING }

    public record Charge(UUID transferId, UUID customerAccountId, long amountCents) {
    }

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, UUID> refunds = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextRefund = new AtomicBoolean();
    private final Queue<Answer> nextAnswers = new ConcurrentLinkedQueue<>();
    private volatile Runnable beforeCharge = () -> { };

    @Override
    public UUID charge(String idempotencyKey, UUID customerAccountId, long amountCents, String description) {
        beforeCharge.run();
        Answer answer = nextAnswers.poll();
        switch (answer == null ? Answer.APPROVE : answer) {
            case DECLINE -> throw new PaymentDeclinedException("INSUFFICIENT_FUNDS", "Insufficient funds.");
            case UNAVAILABLE -> throw new PaymentUnavailableException("Ledger is down.", null);
            case TIME_OUT_AFTER_CHARGING -> {
                record(idempotencyKey, customerAccountId, amountCents);
                throw new PaymentUnavailableException("Read timed out.", null);
            }
            case APPROVE -> {
                return record(idempotencyKey, customerAccountId, amountCents).transferId();
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Override
    public UUID refund(UUID transferId, String idempotencyKey, String reason) {
        if (failNextRefund.compareAndSet(true, false)) {
            throw new PaymentUnavailableException("Ledger is down.", null);
        }
        return refunds.computeIfAbsent(idempotencyKey, key -> UUID.randomUUID());
    }

    private Charge record(String idempotencyKey, UUID customerAccountId, long amountCents) {
        return charges.computeIfAbsent(idempotencyKey, key -> new Charge(UUID.randomUUID(), customerAccountId, amountCents));
    }

    /** Scripts the answers to the next charges, in order; after them every charge is approved. */
    public void answerNext(Answer... answers) {
        nextAnswers.addAll(List.of(answers));
    }

    /** Runs right before each charge, to change the world while the ledger call is in flight. */
    public void beforeCharge(Runnable action) {
        beforeCharge = action;
    }

    public Optional<Charge> chargeOf(UUID bookingId) {
        return Optional.ofNullable(charges.get("booking-" + bookingId));
    }

    public void failNextRefund() {
        failNextRefund.set(true);
    }

    public Optional<UUID> refundOf(UUID bookingId) {
        return Optional.ofNullable(refunds.get("refund-" + bookingId));
    }

    public int refundCount() {
        return refunds.size();
    }

    public int chargeCount() {
        return charges.size();
    }

    public void reset() {
        charges.clear();
        refunds.clear();
        failNextRefund.set(false);
        nextAnswers.clear();
        beforeCharge = () -> { };
    }
}
