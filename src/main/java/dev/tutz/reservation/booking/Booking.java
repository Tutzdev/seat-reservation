package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.AssignedIdEntity;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.Seat;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's claim on a set of seats. While it is {@link BookingStatus#HELD} the seats are kept for
 * this booking until {@code expiresAt}; which seat belongs to whom right now is decided by the seat rows.
 */
@Entity
@Table(name = "bookings")
public class Booking extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(nullable = false)
    private long totalCents;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @OrderBy("seatId")
    private List<BookedSeat> seats = new ArrayList<>();

    protected Booking() {
    }

    private Booking(UUID eventId, List<BookedSeat> seats, Instant now, Duration holdFor) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.status = BookingStatus.HELD;
        this.seats = new ArrayList<>(seats);
        this.totalCents = seats.stream().mapToLong(BookedSeat::priceCents).sum();
        this.expiresAt = now.plus(holdFor);
        this.createdAt = now;
        this.updatedAt = now;
    }

    static Booking hold(UUID eventId, List<Seat> seats, Instant now, Duration holdFor) {
        List<BookedSeat> lines = seats.stream()
                .sorted(Comparator.comparing(Seat::getId))
                .map(BookedSeat::of)
                .toList();
        return new Booking(eventId, lines, now, holdFor);
    }

    /**
     * Cancelling twice is harmless, so a client that retries after a timeout gets the same answer.
     *
     * @return true if this call cancelled the booking, false if it was already cancelled
     */
    boolean cancel(Instant now) {
        if (status == BookingStatus.CANCELLED) {
            return false;
        }
        if (status != BookingStatus.HELD) {
            throw new ConflictException("BOOKING_NOT_ACTIVE",
                    "Booking is %s and can no longer be cancelled.".formatted(status), Map.of("status", status));
        }
        status = BookingStatus.CANCELLED;
        updatedAt = now;
        return true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<BookedSeat> getSeats() {
        return List.copyOf(seats);
    }
}
