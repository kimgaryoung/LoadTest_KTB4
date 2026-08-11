package com.ktb.chatapp.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisRoleTestContainers;
import com.ktb.chatapp.model.User;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
    "socketio.enabled=false",
    "app.cache.user-profile.enabled=true",
    "app.cache.user-profile.ttl=60s"
})
@DisplayName("Redis 사용자 프로필 캐시 통합 테스트")
class UserProfileCacheTest extends RedisRoleTestContainers {

    @Autowired
    private UserProfileCache cache;

    @AfterEach
    void tearDown() {
        cache.evict("cache-user-1");
        cache.evict("cache-user-2");
    }

    @Test
    @DisplayName("공개 프로필만 저장하고 단건 및 일괄 조회한다")
    void putAndGetAll() {
        User first = user("cache-user-1", "첫 번째", "first@example.com");
        User second = user("cache-user-2", "두 번째", "second@example.com");

        cache.putAll(List.of(first, second));

        assertThat(cache.get("cache-user-1")).contains(CachedUserProfile.from(first));
        assertThat(cache.getAll(List.of("cache-user-1", "cache-user-2")))
                .containsEntry("cache-user-1", CachedUserProfile.from(first))
                .containsEntry("cache-user-2", CachedUserProfile.from(second));
    }

    @Test
    @DisplayName("삭제한 프로필은 즉시 캐시 미스로 처리한다")
    void evictRemovesEntry() {
        User user = user("cache-user-1", "삭제 대상", "delete@example.com");
        cache.put(user);

        cache.evict(user.getId());

        assertThat(cache.get(user.getId())).isEmpty();
    }

    private User user(String id, String name, String email) {
        return User.builder()
                .id(id)
                .name(name)
                .email(email)
                .password("must-not-be-cached")
                .profileImage("profiles/" + id + ".jpg")
                .build();
    }
}
