package dev.tutz.reservation.dashboard;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds won and lost per event, for the organizer's live view of a sale. The Micrometer timer counts them for the
 * whole service; this keeps them per event in a Redis hash. Lost holds roll back, so the database never sees them.
 *
 * <p>Best effort: a count that cannot be written is skipped, never a reason to fail the hold.
 */
@Component
public class SaleCounters {

    private static final Logger log = LoggerFactory.getLogger(SaleCounters.class);

    private final StringRedisTemplate redis;

    SaleCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void holdWon(UUID eventId) {
        increment(eventId, "won");
    }

    public void holdLost(UUID eventId) {
        increment(eventId, "lost");
    }

    HoldCounts of(UUID eventId) {
        try {
            Map<Object, Object> counts = redis.opsForHash().entries(key(eventId));
            return new HoldCounts(count(counts, "won"), count(counts, "lost"));
        } catch (DataAccessException exception) {
            log.debug("Hold counts of event {} unavailable: {}", eventId, exception.getMessage());
            return new HoldCounts(0, 0);
        }
    }

    private void increment(UUID eventId, String field) {
        try {
            redis.opsForHash().increment(key(eventId), field, 1);
        } catch (DataAccessException exception) {
            log.debug("Hold count of event {} not recorded: {}", eventId, exception.getMessage());
        }
    }

    private static long count(Map<Object, Object> counts, String field) {
        Object value = counts.get(field);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private static String key(UUID eventId) {
        return "sale:{%s}:holds".formatted(eventId);
    }

    record HoldCounts(long won, long lost) {
    }
}
