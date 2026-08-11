package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RedisRolePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsIndependentAuthAndRealtimeEndpoints() {
        contextRunner.withPropertyValues(
                        "app.redis.auth.host=redis-auth.internal",
                        "app.redis.auth.port=6380",
                        "app.redis.auth.password=auth-secret",
                        "app.redis.auth.connect-timeout=3s",
                        "app.redis.auth.command-timeout=4s",
                        "app.redis.auth.ssl=true",
                        "app.redis.realtime.host=redis-realtime.internal",
                        "app.redis.realtime.port=6381",
                        "app.redis.realtime.password=realtime-secret",
                        "app.redis.realtime.connect-timeout=5s",
                        "app.redis.realtime.command-timeout=6s",
                        "app.redis.realtime.ssl=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisRoleProperties properties = context.getBean(RedisRoleProperties.class);

                    assertThat(properties.getAuth().getHost()).isEqualTo("redis-auth.internal");
                    assertThat(properties.getAuth().getPort()).isEqualTo(6380);
                    assertThat(properties.getAuth().getPassword()).isEqualTo("auth-secret");
                    assertThat(properties.getAuth().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getAuth().getCommandTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.getAuth().isSsl()).isTrue();

                    assertThat(properties.getRealtime().getHost()).isEqualTo("redis-realtime.internal");
                    assertThat(properties.getRealtime().getPort()).isEqualTo(6381);
                    assertThat(properties.getRealtime().getPassword()).isEqualTo("realtime-secret");
                    assertThat(properties.getRealtime().getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getRealtime().getCommandTimeout()).isEqualTo(Duration.ofSeconds(6));
                    assertThat(properties.getRealtime().isSsl()).isFalse();
                });
    }

    @Test
    void rejectsBlankHostAndInvalidPortAtStartup() {
        contextRunner.withPropertyValues(
                        "app.redis.auth.host=",
                        "app.redis.auth.port=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisRoleProperties.class)
    static class TestConfiguration {
    }
}
