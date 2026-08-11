package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corundumstudio.socketio.AuthTokenListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class SocketIOStoreFailureTest {

    @Test
    void redisStoreDoesNotSilentlyFallBackToMemory() {
        SocketIOConfig config = new SocketIOConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 5002);
        ReflectionTestUtils.setField(config, "origin", "*");
        ReflectionTestUtils.setField(config, "storeType", "redis");
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        when(redissonProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> config.socketIOServer(
                        mock(AuthTokenListener.class),
                        mock(MeterRegistry.class),
                        redissonProvider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realtime Redis Redisson client");
    }
}
