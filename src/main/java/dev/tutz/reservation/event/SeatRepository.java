package dev.tutz.reservation.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Ids come from a sequence while the layout is inserted in order, so id order is layout order. */
    List<Seat> findByEventIdOrderById(UUID eventId);

    List<Seat> findByEventIdAndSectionOrderById(UUID eventId, String section);

    List<Seat> findByEventIdAndIdIn(UUID eventId, Collection<Long> ids);

    /**
     * Holds one seat only if nobody else holds it. The WHERE clause is the whole concurrency control:
     * PostgreSQL locks the row, and a second transaction waiting on that lock re-checks the condition
     * once the first one commits, finds the seat HELD and updates nothing. No SELECT ... FOR UPDATE,
     * no version column, no retry loop.
     *
     * @return 1 if this booking got the seat, 0 if it was taken
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s
            set s.status = dev.tutz.reservation.event.SeatStatus.HELD, s.bookingId = :bookingId, s.heldUntil = :heldUntil
            where s.id = :seatId and s.status = dev.tutz.reservation.event.SeatStatus.AVAILABLE
            """)
    int hold(long seatId, UUID bookingId, Instant heldUntil);

    @Query("""
            select new dev.tutz.reservation.event.SectionResponse(
                s.section,
                count(s),
                sum(case when s.status = dev.tutz.reservation.event.SeatStatus.AVAILABLE then 1 else 0 end),
                min(s.priceCents))
            from Seat s
            where s.eventId = :eventId
            group by s.section
            order by min(s.id)
            """)
    List<SectionResponse> summarizeSections(UUID eventId);
}
