package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;

class RedissonConfigTest {

    @Test
    void usesOnlyRealtimeEndpointAndTimeouts() {
        RedisRoleProperties properties = new RedisRoleProperties();
        properties.getAuth().setHost("redis-auth.internal");
        properties.getAuth().setPort(6380);
        properties.getRealtime().setHost("redis-realtime.internal");
        properties.getRealtime().setPort(6381);
        properties.getRealtime().setPassword("realtime-secret");
        properties.getRealtime().setConnectTimeout(Duration.ofSeconds(3));
        properties.getRealtime().setCommandTimeout(Duration.ofSeconds(4));
        properties.getRealtime().setSsl(true);

        Config config = RedissonConfig.createConfig(properties.getRealtime());
        var server = config.useSingleServer();

        assertThat(server.getAddress()).isEqualTo("rediss://redis-realtime.internal:6381");
        assertThat(server.getAddress()).doesNotContain("redis-auth.internal");
        assertThat(config.getPassword()).isEqualTo("realtime-secret");
        assertThat(server.getConnectTimeout()).isEqualTo(3_000);
        assertThat(server.getTimeout()).isEqualTo(4_000);
    }
}
