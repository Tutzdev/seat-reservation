package dev.tutz.reservation.queue;

import dev.tutz.reservation.common.UnavailableException;
import dev.tutz.reservation.event.Event;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The virtual waiting room in front of a big sale, kept in Redis.
 *
 * <p>No background job lets people in. Each newcomer is given an admission time one interval after the one
 * before (see {@code redis/join-waiting-room.lua}), so a crowd that arrives in the same second is spread over
 * minutes and the database only sees the pace the organizer chose. Being let in is then just comparing that
 * time with the clock, the same lazy expiry seat holds use.
 *
 * <p>Redis being down closes the room instead of opening it: letting the whole crowd through at once is the
 * exact failure the room exists to prevent.
 */
@Component
public class WaitingRoom {

    private static final RedisScript<Long> JOIN =
            RedisScript.of(new ClassPathResource("redis/join-waiting-room.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final WaitingRoomProperties properties;

    WaitingRoom(StringRedisTemplate redis, WaitingRoomProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Joining again while the pass is valid keeps the same place; after it expired, the customer goes to the back. */
    QueueTicket join(Event event, UUID customerId, Instant now) {
        long interval = Math.max(1, event.admissionInterval().toMillis());
        Long admittedAt = call(() -> redis.execute(JOIN, List.of(tailKey(event), customerKey(event, customerId)),
                Long.toString(now.toEpochMilli()), Long.toString(interval),
                Long.toString(properties.passTtl().toMillis())));
        return ticket(admittedAt);
    }

    Optional<QueueTicket> ticketOf(Event event, UUID customerId, Instant now) {
        String admittedAt = call(() -> redis.opsForValue().get(customerKey(event, customerId)));
        return Optional.ofNullable(admittedAt)
                .map(value -> ticket(Long.parseLong(value)))
                .filter(ticket -> ticket.isValidAt(now));
    }

    /** Lets the hold through when the event has no waiting room, or when this customer's turn has come. */
    public void requirePass(Event event, UUID customerId, Instant now) {
        if (!event.hasWaitingRoom()) {
            return;
        }
        QueueTicket ticket = ticketOf(event, customerId, now).orElseThrow(() -> new NotAdmittedException(
                "This sale has a waiting room. Join it with POST /api/v1/events/%s/queue.".formatted(event.getId()),
                null));
        if (!ticket.isAdmittedAt(now)) {
            throw new NotAdmittedException("It is not your turn yet. You will be let in at %s."
                    .formatted(ticket.admittedAt()), ticket.waitAt(now));
        }
    }

    private QueueTicket ticket(long admittedAtMillis) {
        Instant admittedAt = Instant.ofEpochMilli(admittedAtMillis);
        return new QueueTicket(admittedAt, admittedAt.plus(properties.passTtl()));
    }

    private static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException exception) {
            throw new UnavailableException("WAITING_ROOM_UNAVAILABLE",
                    "The waiting room cannot be reached right now. Try again in a moment.", exception);
        }
    }

    // The event id in braces is a hash tag: in a Redis Cluster both keys of a room live on the same node,
    // which a script touching both requires.
    private static String tailKey(Event event) {
        return "queue:{%s}:tail".formatted(event.getId());
    }

    private static String customerKey(Event event, UUID customerId) {
        return "queue:{%s}:customer:%s".formatted(event.getId(), customerId);
    }
}
