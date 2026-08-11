package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.bson.Document;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    /** Fetches all visible-room activity counters with one MongoDB aggregation. */
    public Map<String, Integer> countRecentMessagesByRoomIds(Collection<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("room").in(roomIds)
                        .and("timestamp").gte(since)),
                Aggregation.group("room").count().as("count"));
        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, "messages", Document.class);
        return results.getMappedResults().stream().collect(Collectors.toMap(
                document -> document.getString("_id"),
                document -> ((Number) document.get("count")).intValue()));
    }
}
