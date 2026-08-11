package com.ktb.chatapp.service.cache;

import com.ktb.chatapp.model.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis cache-aside store for public user profile fields.
 *
 * <p>Redis failures are deliberately treated as cache misses so that the MongoDB-backed API
 * contract remains available. Keys and the template bean are isolated by name so a dedicated
 * cache/Socket.IO Redis can be wired later without changing callers.</p>
 */
@Slf4j
@Component
public class UserProfileCache {

    private static final String KEY_PREFIX = "cache:user-profile:v1:";
    private static final DefaultRedisScript<Long> WRITE_WITH_TTL = new DefaultRedisScript<>(
            "for i, key in ipairs(KEYS) do "
                    + "redis.call('SET', key, ARGV[i], 'EX', ARGV[#KEYS + 1]); "
                    + "end; return #KEYS;",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public UserProfileCache(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.cache.user-profile.enabled:true}") boolean enabled,
            @Value("${app.cache.user-profile.ttl:60s}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(60) : ttl;
        this.hits = meterRegistry.counter("user.profile.cache.requests", "result", "hit");
        this.misses = meterRegistry.counter("user.profile.cache.requests", "result", "miss");
        this.errors = meterRegistry.counter("user.profile.cache.errors");
    }

    public Optional<CachedUserProfile> get(String userId) {
        return Optional.ofNullable(getAll(List.of(userId)).get(userId));
    }

    public Map<String, CachedUserProfile> getAll(Collection<String> userIds) {
        List<String> ids = userIds == null
                ? List.of()
                : userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (!enabled || ids.isEmpty()) {
            return Map.of();
        }

        List<String> keys = ids.stream().map(UserProfileCache::key).toList();
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            Map<String, CachedUserProfile> result = new LinkedHashMap<>();
            for (int index = 0; index < ids.size(); index++) {
                String value = values != null && index < values.size() ? values.get(index) : null;
                if (value == null) {
                    misses.increment();
                    continue;
                }
                try {
                    CachedUserProfile profile = objectMapper.readValue(value, CachedUserProfile.class);
                    if (profile.id() == null || !profile.id().equals(ids.get(index))) {
                        misses.increment();
                        continue;
                    }
                    result.put(profile.id(), profile);
                    hits.increment();
                } catch (Exception malformedEntry) {
                    misses.increment();
                    errors.increment();
                    log.debug("Ignoring malformed user profile cache entry for {}", ids.get(index));
                }
            }
            return result;
        } catch (RuntimeException redisFailure) {
            misses.increment(ids.size());
            errors.increment();
            log.debug("User profile cache read failed; falling back to MongoDB", redisFailure);
            return Map.of();
        }
    }

    public void put(User user) {
        if (user != null) {
            putAll(List.of(user));
        }
    }

    public void putAll(Collection<User> users) {
        if (!enabled || users == null || users.isEmpty()) {
            return;
        }

        List<User> cacheableUsers = users.stream()
                .filter(user -> user != null && user.getId() != null && !user.getId().isBlank())
                .toList();
        if (cacheableUsers.isEmpty()) {
            return;
        }

        try {
            List<String> keys = cacheableUsers.stream().map(user -> key(user.getId())).toList();
            List<String> arguments = new ArrayList<>(cacheableUsers.size() + 1);
            for (User user : cacheableUsers) {
                arguments.add(objectMapper.writeValueAsString(CachedUserProfile.from(user)));
            }
            arguments.add(String.valueOf(Math.max(1L, ttl.toSeconds())));
            redisTemplate.execute(WRITE_WITH_TTL, keys, arguments.toArray());
        } catch (Exception cacheWriteFailure) {
            errors.increment();
            log.debug("User profile cache write failed; MongoDB remains authoritative", cacheWriteFailure);
        }
    }

    public void evict(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException redisFailure) {
            errors.increment();
            log.debug("User profile cache eviction failed for {}", userId, redisFailure);
        }
    }

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
