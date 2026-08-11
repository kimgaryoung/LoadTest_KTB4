package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.service.SessionMetadata;
import com.ktb.chatapp.service.cache.UserProfileCache;
import com.ktb.chatapp.service.ratelimit.RedisRateLimitStore;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
        "socketio.enabled=false",
        "session.store=redis",
        "ratelimit.store=redis",
        "app.cache.user-profile.enabled=true"
})
class RedisRoleSeparationIntegrationTest extends RedisRoleTestContainers {

    private static final String USER_ID = "role-separated-user";
    private static final String CLIENT_ID = "role-separated-client";

    @Autowired private SessionStore sessionStore;
    @Autowired private RedisRateLimitStore rateLimitStore;
    @Autowired private UserProfileCache userProfileCache;
    @Autowired @Qualifier("authRedisTemplate") private StringRedisTemplate authRedis;
    @Autowired @Qualifier("cacheRedisTemplate") private StringRedisTemplate realtimeRedis;

    @AfterEach
    void cleanKeys() {
        authRedis.delete("login_session:" + USER_ID);
        authRedis.delete("rate_limit:socket:" + CLIENT_ID);
        realtimeRedis.delete("cache:user-profile:v1:" + USER_ID);
    }

    @Test
    void writesEachKeyFamilyOnlyToItsPhysicalRedisRole() {
        long now = Instant.now().toEpochMilli();
        sessionStore.save(Session.builder()
                .userId(USER_ID)
                .sessionId("session-id")
                .createdAt(now)
                .lastActivity(now)
                .metadata(new SessionMetadata("agent", "127.0.0.1", "device"))
                .expiresAt(Instant.now().plusSeconds(1_800))
                .build());
        RateLimitCheckResult result = rateLimitStore.check(CLIENT_ID, 10, Duration.ofMinutes(1));
        userProfileCache.put(User.builder()
                .id(USER_ID)
                .name("Redis Role User")
                .email("role@example.com")
                .profileImage("profiles/role.png")
                .build());

        assertThat(result.allowed()).isTrue();
        assertThat(authRedis.hasKey("login_session:" + USER_ID)).isTrue();
        assertThat(authRedis.hasKey("rate_limit:socket:" + CLIENT_ID)).isTrue();
        assertThat(authRedis.hasKey("cache:user-profile:v1:" + USER_ID)).isFalse();
        assertThat(realtimeRedis.hasKey("login_session:" + USER_ID)).isFalse();
        assertThat(realtimeRedis.hasKey("rate_limit:socket:" + CLIENT_ID)).isFalse();
        assertThat(realtimeRedis.hasKey("cache:user-profile:v1:" + USER_ID)).isTrue();
    }
}
