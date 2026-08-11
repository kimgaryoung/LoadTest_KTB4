package com.ktb.chatapp.service.session;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisRoleTestContainers;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
    "socketio.enabled=false",
    "session.store=redis"
})
@DisplayName("Redis 로그인 세션 저장소 통합 테스트")
class RedisSessionStoreTest extends RedisRoleTestContainers {

    private static final String USER_ID = "redis-session-user";
    private static final long TTL_SECONDS = 1_800L;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        sessionStore.deleteAll(USER_ID);
    }

    @Test
    @DisplayName("검증과 lastActivity 및 TTL 갱신을 한 번의 Redis 스크립트로 처리한다")
    void validateAndTouch_AtomicallyUpdatesSession() {
        long createdAt = Instant.now().minusSeconds(10).toEpochMilli();
        Session original = session(createdAt, createdAt, "session-1");
        sessionStore.replaceByUserId(original);
        long touchedAt = Instant.now().toEpochMilli();

        SessionTouchResult result = sessionStore.validateAndTouch(
                USER_ID, "session-1", touchedAt, TTL_SECONDS * 1_000L, TTL_SECONDS);

        assertThat(result.status()).isEqualTo(SessionTouchResult.Status.VALID);
        assertThat(result.session().getLastActivity()).isEqualTo(touchedAt);
        assertThat(result.session().getMetadata().ipAddress()).isEqualTo("127.0.0.1");
        assertThat(sessionStore.findByUserId(USER_ID).orElseThrow().getLastActivity()).isEqualTo(touchedAt);
    }

    @Test
    @DisplayName("새 세션 교체 후 이전 세션 ID는 원자적으로 거부된다")
    void replace_InvalidatesPreviousSessionId() {
        long now = Instant.now().toEpochMilli();
        sessionStore.replaceByUserId(session(now, now, "old-session"));
        sessionStore.replaceByUserId(session(now, now, "new-session"));

        SessionTouchResult oldResult = sessionStore.validateAndTouch(
                USER_ID, "old-session", now, TTL_SECONDS * 1_000L, TTL_SECONDS);
        SessionTouchResult newResult = sessionStore.validateAndTouch(
                USER_ID, "new-session", now, TTL_SECONDS * 1_000L, TTL_SECONDS);

        assertThat(oldResult.status()).isEqualTo(SessionTouchResult.Status.SESSION_ID_MISMATCH);
        assertThat(newResult.status()).isEqualTo(SessionTouchResult.Status.VALID);
    }

    @Test
    @DisplayName("논리적으로 만료된 세션은 검증 시 즉시 제거된다")
    void validateAndTouch_DeletesExpiredSession() {
        long now = Instant.now().toEpochMilli();
        sessionStore.replaceByUserId(session(now - 10_000L, now - 10_000L, "expired-session"));

        SessionTouchResult result = sessionStore.validateAndTouch(
                USER_ID, "expired-session", now, 1_000L, TTL_SECONDS);

        assertThat(result.status()).isEqualTo(SessionTouchResult.Status.EXPIRED);
        assertThat(sessionStore.findByUserId(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("세션 저장소 연산과 검증 결과 지표를 기록한다")
    void recordsStoreMetrics() {
        long now = Instant.now().toEpochMilli();
        sessionStore.replaceByUserId(session(now, now, "session-metrics"));
        sessionStore.validateAndTouch(
                USER_ID, "session-metrics", now, TTL_SECONDS * 1_000L, TTL_SECONDS);

        assertThat(meterRegistry.get("session.store.operations")
                .tag("store", "redis")
                .tag("operation", "validate_touch")
                .tag("outcome", "success")
                .counter().count()).isPositive();
        assertThat(meterRegistry.get("session.store.validations")
                .tag("store", "redis")
                .tag("result", "valid")
                .counter().count()).isPositive();
    }

    private Session session(long createdAt, long lastActivity, String sessionId) {
        return Session.builder()
                .userId(USER_ID)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .lastActivity(lastActivity)
                .metadata(new SessionMetadata("test-agent", "127.0.0.1", "test-device"))
                .expiresAt(Instant.now().plusSeconds(TTL_SECONDS))
                .build();
    }
}
