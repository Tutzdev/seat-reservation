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
    private Instant salesOpenAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    private Event(String name, String venue, Instant startsAt, Instant salesOpenAt, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.salesOpenAt = salesOpenAt;
        this.createdAt = now;
    }

    /** @param salesOpenAt when seats go on sale; {@code null} puts them on sale right away */
    static Event schedule(String name, String venue, Instant startsAt, Instant salesOpenAt, Instant now) {
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException("EVENT_IN_THE_PAST", "An event must start in the future.");
        }
        Instant opensAt = salesOpenAt != null ? salesOpenAt : now;
        if (!opensAt.isBefore(startsAt)) {
            throw new BusinessRuleException("SALE_OPENS_AFTER_START", "Sales must open before the event starts.");
        }
        return new Event(name.strip(), venue.strip(), startsAt, opensAt, now);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    public boolean isSaleOpen(Instant now) {
        return !now.isBefore(salesOpenAt);
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

    public Instant getSalesOpenAt() {
        return salesOpenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
