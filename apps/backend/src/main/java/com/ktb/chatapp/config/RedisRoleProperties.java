package com.ktb.chatapp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Connection settings for the auth and realtime Redis roles. */
@Validated
@ConfigurationProperties(prefix = "app.redis")
public class RedisRoleProperties {

    @Valid
    @NotNull
    private Endpoint auth = new Endpoint();

    @Valid
    @NotNull
    private Endpoint realtime = new Endpoint();

    public Endpoint getAuth() {
        return auth;
    }

    public void setAuth(Endpoint auth) {
        this.auth = auth;
    }

    public Endpoint getRealtime() {
        return realtime;
    }

    public void setRealtime(Endpoint realtime) {
        this.realtime = realtime;
    }

    public static class Endpoint {

        @NotBlank
        private String host = "localhost";

        @Min(1)
        @Max(65_535)
        private int port = 6379;

        private String password = "";

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull
        private Duration commandTimeout = Duration.ofSeconds(2);

        private boolean ssl;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        @AssertTrue(message = "connect-timeout and command-timeout must be greater than zero")
        public boolean isTimeoutsPositive() {
            return connectTimeout != null
                    && !connectTimeout.isZero()
                    && !connectTimeout.isNegative()
                    && commandTimeout != null
                    && !commandTimeout.isZero()
                    && !commandTimeout.isNegative();
        }
    }
}
