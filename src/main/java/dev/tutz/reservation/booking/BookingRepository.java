package dev.tutz.reservation.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Status changes lock the booking row, so a cancel and the expiry job never act on the same booking at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(UUID id);

    /**
     * Claims a batch of abandoned holds. SKIP LOCKED makes concurrent sweepers (one per instance) split
     * the work instead of queueing behind each other, and skips a booking that is being cancelled right now.
     */
    @Query(value = """
            SELECT id FROM bookings
            WHERE status = 'HELD' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockExpiredHolds(Instant now, int limit);
}
