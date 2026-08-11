package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class AuthRedisConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CacheRedisConfig.class, ConsumerConfiguration.class)
            .withPropertyValues(
                    "app.redis.auth.host=redis-auth.internal",
                    "app.redis.auth.port=6380",
                    "app.redis.realtime.host=redis-realtime.internal",
                    "app.redis.realtime.port=6381");

    @Test
    void authTemplateUsesAuthEndpointAndIsThePrimaryTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory authFactory = context.getBean(
                    "authRedisConnectionFactory", LettuceConnectionFactory.class);
            StringRedisTemplate authTemplate = context.getBean("authRedisTemplate", StringRedisTemplate.class);

            assertThat(authFactory.getHostName()).isEqualTo("redis-auth.internal");
            assertThat(authFactory.getPort()).isEqualTo(6380);
            assertThat(authTemplate.getConnectionFactory()).isSameAs(authFactory);
            assertThat(context.getBean(StringRedisTemplate.class)).isSameAs(authTemplate);
            assertThat(context.getBean(AuthTemplateConsumer.class).template()).isSameAs(authTemplate);
        });
    }

    @Test
    void cacheTemplateUsesRealtimeEndpoint() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            LettuceConnectionFactory authFactory = context.getBean(
                    "authRedisConnectionFactory", LettuceConnectionFactory.class);
            LettuceConnectionFactory realtimeFactory = context.getBean(
                    "realtimeRedisConnectionFactory", LettuceConnectionFactory.class);
            StringRedisTemplate cacheTemplate = context.getBean("cacheRedisTemplate", StringRedisTemplate.class);

            assertThat(realtimeFactory.getHostName()).isEqualTo("redis-realtime.internal");
            assertThat(realtimeFactory.getPort()).isEqualTo(6381);
            assertThat(cacheTemplate.getConnectionFactory()).isSameAs(realtimeFactory);
            assertThat(cacheTemplate.getConnectionFactory()).isNotSameAs(authFactory);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfiguration {

        @Bean
        AuthTemplateConsumer authTemplateConsumer(
                @Qualifier("authRedisTemplate") StringRedisTemplate template) {
            return new AuthTemplateConsumer(template);
        }
    }

    record AuthTemplateConsumer(StringRedisTemplate template) {
    }
}
