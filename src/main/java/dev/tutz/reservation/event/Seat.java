package dev.tutz.reservation.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
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

    /** Changed only by the conditional UPDATEs in {@link SeatRepository}, never through this entity. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status = SeatStatus.AVAILABLE;

    private UUID bookingId;

    private Instant heldUntil;

    protected Seat() {
    }

    Seat(UUID eventId, String section, String rowLabel, int number, long priceCents) {
        this.eventId = eventId;
        this.section = section;
        this.rowLabel = rowLabel;
        this.number = number;
        this.priceCents = priceCents;
    }

    /**
     * A hold past its deadline no longer protects the seat, even before the expiry sweeper releases it.
     * A sold seat has no deadline.
     */
    public boolean isAvailableAt(Instant now) {
        return status == SeatStatus.AVAILABLE || (status == SeatStatus.HELD && !heldUntil.isAfter(now));
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

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }
}
