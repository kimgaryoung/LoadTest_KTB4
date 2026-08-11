package com.ktb.chatapp.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RedisRoleProperties.class)
public class CacheRedisConfig {

    @Bean("authRedisConnectionFactory")
    @Primary
    RedisConnectionFactory authRedisConnectionFactory(RedisRoleProperties properties) {
        return connectionFactory(properties.getAuth());
    }

    @Bean(name = {"authRedisTemplate", "stringRedisTemplate"})
    @Primary
    StringRedisTemplate authRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("authRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean("realtimeRedisConnectionFactory")
    RedisConnectionFactory realtimeRedisConnectionFactory(RedisRoleProperties properties) {
        return connectionFactory(properties.getRealtime());
    }

    @Bean("cacheRedisTemplate")
    StringRedisTemplate cacheRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("realtimeRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    static LettuceConnectionFactory connectionFactory(RedisRoleProperties.Endpoint endpoint) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(endpoint.getHost(), endpoint.getPort());
        if (endpoint.getPassword() != null && !endpoint.getPassword().isBlank()) {
            standalone.setPassword(endpoint.getPassword());
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(endpoint.getConnectTimeout())
                        .build())
                .build();
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                LettuceClientConfiguration.builder()
                        .clientOptions(clientOptions)
                        .commandTimeout(endpoint.getCommandTimeout());
        if (endpoint.isSsl()) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(standalone, client.build());
    }
}
