package com.ktb.chatapp.config;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisRoleTestContainers {

    @Container
    protected static final GenericContainer<?> AUTH_REDIS =
            new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);

    @Container
    protected static final GenericContainer<?> REALTIME_REDIS =
            new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisRoleProperties(DynamicPropertyRegistry registry) {
        registry.add("app.redis.auth.host", AUTH_REDIS::getHost);
        registry.add("app.redis.auth.port", () -> AUTH_REDIS.getMappedPort(6379));
        registry.add("app.redis.realtime.host", REALTIME_REDIS::getHost);
        registry.add("app.redis.realtime.port", () -> REALTIME_REDIS.getMappedPort(6379));
    }
}
