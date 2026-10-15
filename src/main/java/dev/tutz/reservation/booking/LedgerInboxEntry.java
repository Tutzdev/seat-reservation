package dev.tutz.reservation.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A ledger event that was already applied. Rows are only inserted, through {@link LedgerInboxRepository#record}. */
@Entity
@Immutable
@Table(name = "ledger_inbox")
public class LedgerInboxEntry {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private UUID bookingId;

    @Column(nullable = false)
    private UUID transferId;

    @Column(nullable = false)
    private Instant receivedAt;

    protected LedgerInboxEntry() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getTransferId() {
        return transferId;
    }
}
