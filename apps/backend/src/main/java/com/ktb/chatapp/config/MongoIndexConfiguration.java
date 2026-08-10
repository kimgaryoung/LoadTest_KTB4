package com.ktb.chatapp.config;

import com.ktb.chatapp.model.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates performance-critical MongoDB indexes with stable names.
 *
 * <p>Keeping this index explicit makes startup idempotently reconcile the real database instead
 * of assuming annotation-based automatic index creation has already run.</p>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class MongoIndexConfiguration implements ApplicationRunner {

    public static final String MESSAGE_ROOM_TIMESTAMP_INDEX =
            "idx_messages_room_timestamp_desc";

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        mongoTemplate.indexOps(Message.class).createIndex(
                new Index()
                        .on("room", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named(MESSAGE_ROOM_TIMESTAMP_INDEX)
        );
    }
}
