package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@ConditionalOnProperty(name = "session.store", havingValue = "mongo")
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    private final MongoTemplate mongoTemplate;
    private final SessionStoreMetrics metrics;

    public SessionMongoStore(
            SessionRepository sessionRepository,
            MongoTemplate mongoTemplate,
            SessionStoreMetrics metrics) {
        this.sessionRepository = sessionRepository;
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
    }
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return metrics.record("mongo", "find", () -> sessionRepository.findByUserId(userId));
    }
    
    @Override
    public Session save(Session session) {
        return metrics.record("mongo", "save", () -> sessionRepository.save(session));
    }

    @Override
    public Session replaceByUserId(Session session) {
        Query query = Query.query(Criteria.where("userId").is(session.getUserId()));
        Update update = new Update()
                .set("userId", session.getUserId())
                .set("sessionId", session.getSessionId())
                .set("createdAt", session.getCreatedAt())
                .set("lastActivity", session.getLastActivity())
                .set("metadata", session.getMetadata())
                .set("expiresAt", session.getExpiresAt());

        return metrics.record("mongo", "replace", () -> mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Session.class));
    }

    @Override
    public SessionTouchResult validateAndTouch(
            String userId,
            String sessionId,
            long nowEpochMillis,
            long timeoutMillis,
            long ttlSeconds) {
        SessionTouchResult result = metrics.record("mongo", "validate_touch", () -> {
            Query validSession = Query.query(Criteria.where("userId").is(userId)
                    .and("sessionId").is(sessionId)
                    .and("lastActivity").gte(nowEpochMillis - timeoutMillis));
            Update touch = new Update()
                    .set("lastActivity", nowEpochMillis)
                    .set("expiresAt", Instant.ofEpochMilli(nowEpochMillis).plusSeconds(ttlSeconds));
            Session updated = mongoTemplate.findAndModify(
                    validSession,
                    touch,
                    FindAndModifyOptions.options().returnNew(true),
                    Session.class);
            if (updated != null) {
                return SessionTouchResult.valid(updated);
            }

            Session existing = sessionRepository.findByUserId(userId).orElse(null);
            if (existing == null) {
                return SessionTouchResult.invalid(SessionTouchResult.Status.NOT_FOUND);
            }
            if (!sessionId.equals(existing.getSessionId())) {
                return SessionTouchResult.invalid(SessionTouchResult.Status.SESSION_ID_MISMATCH);
            }
            sessionRepository.delete(existing);
            return SessionTouchResult.invalid(SessionTouchResult.Status.EXPIRED);
        });
        metrics.recordValidation("mongo", result.status());
        return result;
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        metrics.record("mongo", "delete", () -> {
            Session session = sessionRepository.findByUserId(userId).orElse(null);
            if (session != null && sessionId.equals(session.getSessionId())) {
                sessionRepository.delete(session);
            }
        });
    }
    
    @Override
    public void deleteAll(String userId) {
        metrics.record("mongo", "delete_all", () -> sessionRepository.deleteByUserId(userId));
    }
}
