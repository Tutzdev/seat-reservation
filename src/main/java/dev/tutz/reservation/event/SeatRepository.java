package dev.tutz.reservation.event;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Ids come from a sequence while the layout is inserted in order, so id order is layout order. */
    List<Seat> findByEventIdOrderById(UUID eventId);

    List<Seat> findByEventIdAndSectionOrderById(UUID eventId, String section);

    @Query("""
            select new dev.tutz.reservation.event.SectionResponse(s.section, count(s), min(s.priceCents))
            from Seat s
            where s.eventId = :eventId
            group by s.section
            order by min(s.id)
            """)
    List<SectionResponse> summarizeSections(UUID eventId);
}
