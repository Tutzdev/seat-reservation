package dev.tutz.reservation.ratelimit;

import dev.tutz.reservation.ratelimit.RateLimitProperties.Limit;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Rate limits shared by every instance of the service, kept in Redis with the GCRA ({@code redis/rate-limit.lua}):
 * one key and one round trip per request, measured with the application's clock like every other deadline.
 *
 * <p>Fails open: when Redis cannot be reached, requests go through and a warning is logged. A limit protects the
 * service from abuse; it should not take the whole API down with it. Both outcomes are counted
 * ({@code reservation.rate_limit.rejected} and {@code reservation.rate_limit.bypassed}, by {@code limit}).
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final RedisScript<Long> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/rate-limit.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    RateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    /**
     * Takes one request from {@code client}'s allowance under the limit called {@code name}.
     *
     * @return how long to wait, zero when the request may go ahead
     */
    public Duration acquire(String name, String client, Limit limit, Instant now) {
        Long wait = run(name, client, limit, now, 1);
        if (wait == null || wait <= 0) {
            return Duration.ZERO;
        }
        meters.counter("reservation.rate_limit.rejected", "limit", name).increment();
        return Duration.ofMillis(wait);
    }

    /** Gives back a request taken with {@link #acquire}, for one that should not count after all. */
    public void release(String name, String client, Limit limit, Instant now) {
        run(name, client, limit, now, -1);
    }

    private Long run(String name, String client, Limit limit, Instant now, int cost) {
        try {
            return redis.execute(SCRIPT, List.of("rate:%s:%s".formatted(name, client)),
                    Long.toString(now.toEpochMilli()), Long.toString(Math.max(1, limit.interval().toMillis())),
                    Integer.toString(limit.requests()), Integer.toString(cost));
        } catch (DataAccessException exception) {
            log.warn("Rate limit {} not applied to {}, Redis cannot be reached: {}", name, client,
                    exception.getMessage());
            meters.counter("reservation.rate_limit.bypassed", "limit", name).increment();
            return null;
        }
    }
}
