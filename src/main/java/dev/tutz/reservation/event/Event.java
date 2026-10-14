package dev.tutz.reservation.event;

import dev.tutz.reservation.common.AssignedIdEntity;
import dev.tutz.reservation.common.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String venue;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    private Event(String name, String venue, Instant startsAt, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.createdAt = now;
    }

    static Event schedule(String name, String venue, Instant startsAt, Instant now) {
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException("EVENT_IN_THE_PAST", "An event must start in the future.");
        }
        return new Event(name.strip(), venue.strip(), startsAt, now);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
