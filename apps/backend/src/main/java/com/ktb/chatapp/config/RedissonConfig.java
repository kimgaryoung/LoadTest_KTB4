package com.ktb.chatapp.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "socketio.store", havingValue = "redis")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedisRoleProperties properties) {
        Config config = createConfig(properties.getRealtime());
        log.info("Realtime Redis client configured for Socket.IO shared state");
        return Redisson.create(config);
    }

    static Config createConfig(RedisRoleProperties.Endpoint endpoint) {
        Config config = new Config();
        String scheme = endpoint.isSsl() ? "rediss://" : "redis://";
        var server = config.useSingleServer()
                .setAddress(scheme + endpoint.getHost() + ":" + endpoint.getPort())
                .setConnectTimeout(Math.toIntExact(endpoint.getConnectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(endpoint.getCommandTimeout().toMillis()));
        if (endpoint.getPassword() != null && !endpoint.getPassword().isBlank()) {
            config.setPassword(endpoint.getPassword());
        }
        return config;
    }
}
