package dev.tutz.reservation.event;

import dev.tutz.reservation.common.AssignedIdEntity;
import dev.tutz.reservation.common.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
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

    /** {@code null} when the sale has no waiting room. */
    private Integer admitsPerMinute;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    private Event(String name, String venue, Instant startsAt, Instant salesOpenAt, Integer admitsPerMinute,
            Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.salesOpenAt = salesOpenAt;
        this.admitsPerMinute = admitsPerMinute;
        this.createdAt = now;
    }

    /**
     * @param salesOpenAt when seats go on sale; {@code null} puts them on sale right away
     * @param admitsPerMinute how many buyers the waiting room lets in per minute; {@code null} for no waiting room
     */
    static Event schedule(String name, String venue, Instant startsAt, Instant salesOpenAt, Integer admitsPerMinute,
            Instant now) {
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException("EVENT_IN_THE_PAST", "An event must start in the future.");
        }
        Instant opensAt = salesOpenAt != null ? salesOpenAt : now;
        if (!opensAt.isBefore(startsAt)) {
            throw new BusinessRuleException("SALE_OPENS_AFTER_START", "Sales must open before the event starts.");
        }
        return new Event(name.strip(), venue.strip(), startsAt, opensAt, admitsPerMinute, now);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    public boolean isSaleOpen(Instant now) {
        return !now.isBefore(salesOpenAt);
    }

    public boolean hasWaitingRoom() {
        return admitsPerMinute != null;
    }

    /** How long the waiting room waits between letting in one buyer and the next. */
    public Duration admissionInterval() {
        if (!hasWaitingRoom()) {
            throw new IllegalStateException("Event %s has no waiting room.".formatted(id));
        }
        return Duration.ofMinutes(1).dividedBy(admitsPerMinute);
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

    public Integer getAdmitsPerMinute() {
        return admitsPerMinute;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
