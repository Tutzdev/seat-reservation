package dev.tutz.reservation.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One physical seat of one event. Seats reference their event by id instead of a JPA association:
 * an event can have thousands of seats and nothing should ever load them through the event.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seats_id")
    @SequenceGenerator(name = "seats_id", sequenceName = "seats_id_seq", allocationSize = 100)
    private Long id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 40)
    private String section;

    @Column(nullable = false, length = 8)
    private String rowLabel;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private long priceCents;

    protected Seat() {
    }

    Seat(UUID eventId, String section, String rowLabel, int number, long priceCents) {
        this.eventId = eventId;
        this.section = section;
        this.rowLabel = rowLabel;
        this.number = number;
        this.priceCents = priceCents;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSection() {
        return section;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getNumber() {
        return number;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
