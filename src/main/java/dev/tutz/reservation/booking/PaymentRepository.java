package dev.tutz.reservation.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Payments are changed only while their booking row is locked, so they need no lock of their own. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Payments still waiting for the ledger, oldest first, that nobody has touched since {@code before}. */
    @Query(value = """
            SELECT booking_id FROM payments
            WHERE status IN ('PENDING', 'REFUND_PENDING') AND updated_at <= :before
            ORDER BY updated_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnsettled(Instant before, int limit);
}
