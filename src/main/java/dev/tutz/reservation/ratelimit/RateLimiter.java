package dev.tutz.reservation.ratelimit;

import dev.tutz.reservation.ratelimit.RateLimitProperties.Limit;
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
 * service from abuse; it should not take the whole API down with it.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final RedisScript<Long> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/rate-limit.lua"), Long.class);

    private final StringRedisTemplate redis;

    RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Takes one request from {@code key}'s allowance. @return how long to wait, zero when it may go ahead */
    public Duration acquire(String key, Limit limit, Instant now) {
        Long wait = run(key, limit, now, 1);
        return wait == null || wait <= 0 ? Duration.ZERO : Duration.ofMillis(wait);
    }

    /** Gives back a request taken with {@link #acquire}, for one that should not count after all. */
    public void release(String key, Limit limit, Instant now) {
        run(key, limit, now, -1);
    }

    private Long run(String key, Limit limit, Instant now, int cost) {
        try {
            return redis.execute(SCRIPT, List.of("rate:" + key), Long.toString(now.toEpochMilli()),
                    Long.toString(Math.max(1, limit.interval().toMillis())), Integer.toString(limit.requests()),
                    Integer.toString(cost));
        } catch (DataAccessException exception) {
            log.warn("Rate limit {} not applied, Redis cannot be reached: {}", key, exception.getMessage());
            return null;
        }
    }
}
