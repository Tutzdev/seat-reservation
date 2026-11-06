package dev.tutz.reservation.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import dev.tutz.reservation.ratelimit.RateLimitProperties.Limit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** A rate limit protects the API from abuse; Redis being down must not take the API down with it. */
@ExtendWith(MockitoExtension.class)
class RateLimiterOutageTests {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Limit TEN_PER_MINUTE = new Limit(10, Duration.ofMinutes(1));

    @Mock
    private StringRedisTemplate redis;

    @Test
    void requestsGoThroughWhenRedisCannotBeReached() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RateLimiter limiter = new RateLimiter(redis, meters);

        assertThat(limiter.acquire("customer", "42", TEN_PER_MINUTE, NOW)).isZero();
        limiter.release("customer", "42", TEN_PER_MINUTE, NOW);
        assertThat(meters.counter("reservation.rate_limit.bypassed", "limit", "customer").count()).isEqualTo(2);
    }
}
