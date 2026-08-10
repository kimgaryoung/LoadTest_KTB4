package com.ktb.chatapp.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor for non-critical chat side effects. */
@Configuration
@EnableAsync
public class BackgroundTaskConfig {

    @Bean("chatBackgroundExecutor")
    TaskExecutor chatBackgroundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2_000);
        executor.setThreadNamePrefix("chat-bg-");
        // Room activity and AI triggers are optional side effects. Dropping a
        // saturated task is preferable to blocking the Socket.IO event loop.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
