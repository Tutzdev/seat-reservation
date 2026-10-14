package dev.tutz.reservation.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Page<Event> findByStartsAtAfter(Instant instant, Pageable pageable);
}
