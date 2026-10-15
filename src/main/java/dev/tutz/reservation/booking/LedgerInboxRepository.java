package dev.tutz.reservation.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LedgerInboxRepository extends JpaRepository<LedgerInboxEntry, UUID> {

    /**
     * Claims an event for the current transaction. A concurrent delivery of the same event waits on the
     * primary key until this transaction ends, and then gets 0 if it committed.
     *
     * @return 1 if the event is new, 0 if it was already applied
     */
    @Modifying
    @Query(value = """
            INSERT INTO ledger_inbox (event_id, booking_id, transfer_id, received_at)
            VALUES (:eventId, :bookingId, :transferId, :receivedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int record(UUID eventId, UUID bookingId, UUID transferId, Instant receivedAt);

    List<LedgerInboxEntry> findByBookingId(UUID bookingId);
}
