package dev.tutz.reservation.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.tutz.reservation.common.UnavailableException;
import dev.tutz.reservation.event.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Without Redis the room stays closed: letting the whole crowd through is what it exists to prevent. */
@ExtendWith(MockitoExtension.class)
class WaitingRoomOutageTests {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private Event event;

    @Test
    void aHoldIsRefusedWhenRedisCannotBeReached() {
        when(event.hasWaitingRoom()).thenReturn(true);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));
        WaitingRoom room = new WaitingRoom(redis, new WaitingRoomProperties(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> room.requirePass(event, UUID.randomUUID(), NOW))
                .isInstanceOf(UnavailableException.class)
                .extracting("code").isEqualTo("WAITING_ROOM_UNAVAILABLE");
    }
}
