package dev.tutz.reservation.booking;

import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Housekeeping for abandoned holds: marks them EXPIRED and clears their seats. Correctness does not
 * depend on it, because an expired hold already counts as free when someone else tries to hold the
 * seat. The sweeper only keeps the stored state honest for reports and support.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final BookingProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HoldExpirySweeper(BookingRepository bookings, SeatRepository seats, BookingProperties properties,
            Clock clock, TransactionTemplate transactions) {
        this.bookings = bookings;
        this.seats = seats;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${reservation.bookings.sweep.interval}",
            initialDelayString = "${reservation.bookings.sweep.interval}")
    public void sweep() {
        int total = 0;
        int expired;
        do {
            expired = sweepBatch();
            total += expired;
        } while (expired == properties.sweep().batchSize());

        if (total > 0) {
            log.info("Expired {} abandoned holds", total);
        }
    }

    /** One short transaction per batch, so row locks are never held for the whole backlog. */
    private int sweepBatch() {
        Integer expired = transactions.execute(status -> {
            Instant now = clock.instant();
            List<UUID> ids = bookings.lockExpiredHolds(now, properties.sweep().batchSize());
            if (ids.isEmpty()) {
                return 0;
            }
            bookings.findAllById(ids).forEach(booking -> booking.expire(now));
            seats.releaseAll(ids);
            return ids.size();
        });
        return expired == null ? 0 : expired;
    }
}
