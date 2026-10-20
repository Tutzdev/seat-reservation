package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.AssignedIdEntity;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.ResourceNotFoundException;
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

    @Column(updatable = false)
    private UUID customerId;

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

    @Column(nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @ElementCollection
    @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @OrderBy("seatId")
    private List<BookedSeat> seats = new ArrayList<>();

    protected Booking() {
    }

    private Booking(HoldCommand command, List<BookedSeat> seats, Instant now, Duration holdFor) {
        this.id = UUID.randomUUID();
        this.eventId = command.eventId();
        this.customerId = command.customerId();
        this.status = BookingStatus.HELD;
        this.seats = new ArrayList<>(seats);
        this.totalCents = seats.stream().mapToLong(BookedSeat::priceCents).sum();
        this.expiresAt = now.plus(holdFor);
        this.createdAt = now;
        this.updatedAt = now;
        this.idempotencyKey = command.idempotencyKey();
        this.requestFingerprint = command.fingerprint();
    }

    static Booking hold(HoldCommand command, List<Seat> seats, Instant now, Duration holdFor) {
        List<BookedSeat> lines = seats.stream()
                .sorted(Comparator.comparing(Seat::getId))
                .map(BookedSeat::of)
                .toList();
        return new Booking(command, lines, now, holdFor);
    }

    boolean wasRequestedWith(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    boolean hasIdempotencyKey(String key) {
        return idempotencyKey.equals(key);
    }

    /**
     * Someone else's booking is answered exactly like a booking that does not exist, so a customer cannot
     * find out which booking ids are in use.
     */
    void requireOwner(UUID customerId) {
        if (!customerId.equals(this.customerId)) {
            throw new ResourceNotFoundException("Booking %s not found.".formatted(id));
        }
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
        BookingStatus current = statusAt(now);
        if (current != BookingStatus.HELD) {
            throw new ConflictException("BOOKING_NOT_ACTIVE",
                    "Booking is %s and can no longer be cancelled.".formatted(current), Map.of("bookingStatus", current));
        }
        status = BookingStatus.CANCELLED;
        updatedAt = now;
        return true;
    }

    /**
     * Called once the payment is captured and the seats are sold. It checks the stored status, not the
     * deadline: a payment that arrives just after the deadline still confirms the booking if its seats
     * were not taken in the meantime.
     */
    void confirm(Instant now) {
        if (status != BookingStatus.HELD) {
            throw new IllegalStateException("Booking %s is %s and cannot be confirmed.".formatted(id, status));
        }
        status = BookingStatus.CONFIRMED;
        updatedAt = now;
    }

    /** Called by the expiry sweeper, which only picks bookings that are HELD and past their deadline. */
    void expire(Instant now) {
        if (status != BookingStatus.HELD || now.isBefore(expiresAt)) {
            throw new IllegalStateException("Booking %s is not an expired hold.".formatted(id));
        }
        status = BookingStatus.EXPIRED;
        updatedAt = now;
    }

    /**
     * The status as clients should see it. A hold past its deadline is already expired, even if the sweeper
     * has not written that down yet: its seats can be taken by anyone at this point.
     */
    public BookingStatus statusAt(Instant now) {
        if (status == BookingStatus.HELD && !now.isBefore(expiresAt)) {
            return BookingStatus.EXPIRED;
        }
        return status;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getCustomerId() {
        return customerId;
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
