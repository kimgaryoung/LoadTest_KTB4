package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis-backed login session store with atomic sliding-expiration validation. */
@Component
@ConditionalOnProperty(name = "session.store", havingValue = "redis", matchIfMissing = true)
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "login_session:";

    private static final DefaultRedisScript<List> WRITE_SESSION = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], "
                    + "'sessionId', ARGV[1], 'createdAt', ARGV[2], 'lastActivity', ARGV[3], "
                    + "'metadata', ARGV[4], 'expiresAt', ARGV[5]); "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[6]); return {1};",
            List.class);

    private static final DefaultRedisScript<List> VALIDATE_AND_TOUCH = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return {0}; end; "
                    + "local storedSessionId = redis.call('HGET', KEYS[1], 'sessionId'); "
                    + "if storedSessionId ~= ARGV[1] then return {1}; end; "
                    + "local lastActivity = tonumber(redis.call('HGET', KEYS[1], 'lastActivity') or '0'); "
                    + "if tonumber(ARGV[2]) - lastActivity > tonumber(ARGV[3]) then "
                    + "redis.call('DEL', KEYS[1]); return {2}; end; "
                    + "redis.call('HSET', KEYS[1], 'lastActivity', ARGV[2], 'expiresAt', ARGV[4]); "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[5]); "
                    + "return {3, redis.call('HGET', KEYS[1], 'createdAt'), ARGV[2], "
                    + "redis.call('HGET', KEYS[1], 'metadata') or ''};",
            List.class);

    private static final DefaultRedisScript<List> DELETE_IF_MATCHES = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1] then "
                    + "return {redis.call('DEL', KEYS[1])}; end; return {0};",
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreMetrics metrics;

    public RedisSessionStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SessionStoreMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        return metrics.record("redis", "find", () -> {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(key(userId));
            return values.isEmpty() ? Optional.empty() : Optional.of(toSession(userId, values));
        });
    }

    @Override
    public Session save(Session session) {
        return write(session, "save");
    }

    @Override
    public Session replaceByUserId(Session session) {
        return write(session, "replace");
    }

    @Override
    @SuppressWarnings("unchecked")
    public SessionTouchResult validateAndTouch(
            String userId,
            String sessionId,
            long nowEpochMillis,
            long timeoutMillis,
            long ttlSeconds) {
        SessionTouchResult result = metrics.record("redis", "validate_touch", () -> {
            long expiresAt = nowEpochMillis + Duration.ofSeconds(ttlSeconds).toMillis();
            List<Object> response = (List<Object>) redisTemplate.execute(
                    VALIDATE_AND_TOUCH,
                    List.of(key(userId)),
                    sessionId,
                    String.valueOf(nowEpochMillis),
                    String.valueOf(timeoutMillis),
                    String.valueOf(expiresAt),
                    String.valueOf(ttlSeconds));
            long status = response == null || response.isEmpty() ? 0L : asLong(response.getFirst());
            if (status == 0L) {
                return SessionTouchResult.invalid(SessionTouchResult.Status.NOT_FOUND);
            }
            if (status == 1L) {
                return SessionTouchResult.invalid(SessionTouchResult.Status.SESSION_ID_MISMATCH);
            }
            if (status == 2L) {
                return SessionTouchResult.invalid(SessionTouchResult.Status.EXPIRED);
            }

            Session session = Session.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .createdAt(asLong(response.get(1)))
                    .lastActivity(nowEpochMillis)
                    .metadata(readMetadata(asString(response.get(3))))
                    .expiresAt(Instant.ofEpochMilli(expiresAt))
                    .build();
            return SessionTouchResult.valid(session);
        });
        metrics.recordValidation("redis", result.status());
        return result;
    }

    @Override
    public void deleteAll(String userId) {
        metrics.record("redis", "delete_all", () -> redisTemplate.delete(key(userId)));
    }

    @Override
    public void delete(String userId, String sessionId) {
        metrics.record("redis", "delete", () -> redisTemplate.execute(
                DELETE_IF_MATCHES, List.of(key(userId)), sessionId));
    }

    private Session write(Session session, String operation) {
        return metrics.record("redis", operation, () -> {
            long ttlSeconds = Math.max(
                    1L,
                    Duration.between(Instant.now(), session.getExpiresAt()).getSeconds());
            redisTemplate.execute(
                    WRITE_SESSION,
                    List.of(key(session.getUserId())),
                    session.getSessionId(),
                    String.valueOf(session.getCreatedAt()),
                    String.valueOf(session.getLastActivity()),
                    writeMetadata(session.getMetadata()),
                    String.valueOf(session.getExpiresAt().toEpochMilli()),
                    String.valueOf(ttlSeconds));
            return session;
        });
    }

    private Session toSession(String userId, Map<Object, Object> values) {
        return Session.builder()
                .userId(userId)
                .sessionId(value(values, "sessionId"))
                .createdAt(asLong(value(values, "createdAt")))
                .lastActivity(asLong(value(values, "lastActivity")))
                .metadata(readMetadata(value(values, "metadata")))
                .expiresAt(Instant.ofEpochMilli(asLong(value(values, "expiresAt"))))
                .build();
    }

    private String writeMetadata(SessionMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize session metadata", exception);
        }
    }

    private SessionMetadata readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, SessionMetadata.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize session metadata", exception);
        }
    }

    private static String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? "" : value.toString();
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long asLong(Object value) {
        return Long.parseLong(asString(value));
    }

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
