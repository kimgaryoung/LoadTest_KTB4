package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import com.ktb.chatapp.service.session.SessionTouchResult;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionStore sessionStore;
    private static final long VALIDATION_CACHE_MILLIS = 1_000L;
    private final ConcurrentHashMap<String, CachedSession> validationCache = new ConcurrentHashMap<>();
    public static final long SESSION_TTL_SEC = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();
    private static final long SESSION_TIMEOUT = SESSION_TTL_SEC * 1000;

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SessionData toSessionData(Session session) {
        return SessionData.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .lastActivity(session.getLastActivity())
                .metadata(session.getMetadata())
                .build();
    }

    public SessionCreationResult createSession(String userId, SessionMetadata metadata) {
        try {
            String sessionId = generateSessionId();
            long now = Instant.now().toEpochMilli();
            
            Session session = Session.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .createdAt(now)
                    .lastActivity(now)
                    .metadata(metadata)
                    .expiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC))
                    .build();

            session = sessionStore.replaceByUserId(session);
            invalidateCache(userId, null);
            
            SessionData sessionData = toSessionData(session);

            return SessionCreationResult.builder()
                    .sessionId(sessionId)
                    .expiresIn(SESSION_TTL_SEC)
                    .sessionData(sessionData)
                    .build();

        } catch (Exception e) {
            log.error("Session creation error for userId: {}", userId, e);
            throw new RuntimeException("세션 생성 중 오류가 발생했습니다.", e);
        }
    }

    public SessionValidationResult validateSession(String userId, String sessionId) {
        try {
            if (userId == null || sessionId == null) {
                log.warn("validateSession called with null parameters: userId={}, sessionId={}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_PARAMETERS", "유효하지 않은 세션 파라미터");
            }

            long now = Instant.now().toEpochMilli();
            String cacheKey = userId + ":" + sessionId;
            CachedSession cached = validationCache.get(cacheKey);
            if (cached != null && now - cached.validatedAt() < VALIDATION_CACHE_MILLIS) {
                return SessionValidationResult.valid(cached.sessionData());
            }

            SessionTouchResult touchResult = sessionStore.validateAndTouch(
                    userId, sessionId, now, SESSION_TIMEOUT, SESSION_TTL_SEC);

            if (touchResult.status() == SessionTouchResult.Status.NOT_FOUND) {
                log.warn("No session found for userId: {}", userId);
                return SessionValidationResult.invalid("INVALID_SESSION", "세션을 찾을 수 없습니다.");
            }

            if (touchResult.status() == SessionTouchResult.Status.SESSION_ID_MISMATCH) {
                log.warn("Session ID mismatch for userId: {}. Provided: {}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_SESSION", "잘못된 세션 ID입니다.");
            }

            if (touchResult.status() == SessionTouchResult.Status.EXPIRED) {
                log.warn("Session timed out for userId: {}, sessionId: {}", userId, sessionId);
                invalidateCache(userId, sessionId);
                return SessionValidationResult.invalid("SESSION_EXPIRED", "세션이 만료되었습니다.");
            }

            Session session = touchResult.session();
            SessionData sessionData = toSessionData(session);
            validationCache.put(cacheKey, new CachedSession(sessionData, now));
            return SessionValidationResult.valid(sessionData);

        } catch (Exception e) {
            log.error("Session validation error for userId: {}, sessionId: {}", userId, sessionId, e);
            return SessionValidationResult.invalid("VALIDATION_ERROR", "세션 검증 중 오류가 발생했습니다.");
        }
    }

    public void updateLastActivity(String userId) {
        try {
            if (userId == null) {
                log.warn("updateLastActivity called with null userId");
                return;
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            if (session == null) {
                log.debug("No session found to update last activity for user: {}", userId);
                return;
            }

            session.setLastActivity(Instant.now().toEpochMilli());
            session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
            sessionStore.save(session);
            
        } catch (Exception e) {
            log.error("Failed to update session activity for user: {}", userId, e);
        }
    }

    public void removeSession(String userId, String sessionId) {
        try {
            invalidateCache(userId, sessionId);
            if (sessionId != null) {
                sessionStore.delete(userId, sessionId);
            } else {
                sessionStore.deleteAll(userId);
            }
        } catch (Exception e) {
            log.error("Session removal error for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public void removeSession(String userId) {
        removeSession(userId, null);
    }

    public void removeAllUserSessions(String userId) {
        try {
            invalidateCache(userId, null);
            sessionStore.deleteAll(userId);
        } catch (Exception e) {
            log.error("Remove all sessions error for userId: {}", userId, e);
            throw new RuntimeException("모든 세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    private void invalidateCache(String userId, String sessionId) {
        if (userId == null) {
            return;
        }
        if (sessionId != null) {
            validationCache.remove(userId + ":" + sessionId);
        } else {
            String prefix = userId + ":";
            validationCache.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private record CachedSession(SessionData sessionData, long validatedAt) {}

    public SessionData getActiveSession(String userId) {
        try {
            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                return null;
            }

            return toSessionData(session);
        } catch (Exception e) {
            log.error("Get active session error for userId: {}", userId, e);
            return null;
        }
    }
    
}
