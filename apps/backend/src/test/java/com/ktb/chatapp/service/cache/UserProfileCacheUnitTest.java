package com.ktb.chatapp.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class UserProfileCacheUnitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void redisReadFailureIsTreatedAsCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        UserProfileCache cache = new UserProfileCache(
                redisTemplate,
                JsonMapper.builder().build(),
                new SimpleMeterRegistry(),
                true,
                Duration.ofSeconds(60));

        assertThat(cache.getAll(List.of("user-1", "user-2"))).isEmpty();
    }
}
