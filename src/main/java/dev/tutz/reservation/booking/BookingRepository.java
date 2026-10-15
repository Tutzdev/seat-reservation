package dev.tutz.reservation.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** The seat lines are fetched in the same query, since a replay always returns them. */
    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /** Status changes lock the booking row, so a cancel and the expiry job never act on the same booking at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(UUID id);

    /**
     * Claims a batch of abandoned holds. SKIP LOCKED makes concurrent sweepers (one per instance) split
     * the work instead of queueing behind each other, and skips a booking that is being cancelled right now.
     *
     * <p>A hold with a charge in flight is not abandoned. Releasing its seats would turn a payment that lands
     * a little late into a refund, even when nobody wanted those seats; the payment settles it instead.
     */
    @Query(value = """
            SELECT b.id FROM bookings b
            WHERE b.status = 'HELD' AND b.expires_at <= :now
              AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.booking_id = b.id AND p.status = 'PENDING')
            ORDER BY b.expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockExpiredHolds(Instant now, int limit);
}
