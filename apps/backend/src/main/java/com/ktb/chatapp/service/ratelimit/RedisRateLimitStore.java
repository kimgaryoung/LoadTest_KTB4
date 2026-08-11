package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.service.RateLimitCheckResult;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Atomic, shared fixed-window rate limiter backed by Redis. */
@Slf4j
@Component
@ConditionalOnProperty(name = "ratelimit.store", havingValue = "redis", matchIfMissing = true)
public class RedisRateLimitStore {

    private static final DefaultRedisScript<List> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return {count, redis.call('TTL', KEYS[1])};", List.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitStore(@Qualifier("authRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @SuppressWarnings("unchecked")
    public RateLimitCheckResult check(String clientId, int maxRequests, Duration window) {
        long windowSeconds = Math.max(1L, window == null ? 1L : window.getSeconds());
        long nowSeconds = java.time.Instant.now().getEpochSecond();
        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    INCREMENT_WITH_TTL, List.of("rate_limit:socket:" + clientId), String.valueOf(windowSeconds));
            long count = result == null || result.isEmpty() ? 1L : result.getFirst();
            long ttl = result == null || result.size() < 2 ? windowSeconds : Math.max(1L, result.get(1));
            long resetAt = nowSeconds + ttl;
            if (count > maxRequests) {
                return RateLimitCheckResult.rejected(maxRequests, windowSeconds, resetAt, ttl);
            }
            return RateLimitCheckResult.allowed(
                    maxRequests, Math.max(0, maxRequests - (int) count), windowSeconds, resetAt, ttl);
        } catch (Exception exception) {
            log.error("Redis rate limit check failed for client {}", clientId, exception);
            return RateLimitCheckResult.allowed(maxRequests, maxRequests, windowSeconds,
                    nowSeconds + windowSeconds, windowSeconds);
        }
    }
}
