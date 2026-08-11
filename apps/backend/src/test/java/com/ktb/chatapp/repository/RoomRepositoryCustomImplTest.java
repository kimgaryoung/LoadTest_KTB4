package com.ktb.chatapp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.ktb.chatapp.model.Room;
import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class RoomRepositoryCustomImplTest {

    @Test
    void appliesCompositeKeysetPredicateSortAndLimit() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        RoomRepositoryCustomImpl repository = new RoomRepositoryCustomImpl(mongoTemplate);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 11, 12, 0);

        repository.findPageAfter(createdAt, "room-020", 21);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(Room.class));
        Query query = queryCaptor.getValue();

        assertEquals(21, query.getLimit());
        assertEquals(-1, query.getSortObject().get("createdAt"));
        assertEquals(-1, query.getSortObject().get("_id"));
        List<Document> alternatives = query.getQueryObject().getList("$or", Document.class);
        assertTrue(alternatives.get(0).get("createdAt", Document.class).containsKey("$lt"));
        List<Document> tieBreakers = alternatives.get(1).getList("$and", Document.class);
        assertEquals(createdAt, tieBreakers.get(0).get("createdAt"));
        assertEquals("room-020", tieBreakers.get(1).get("_id", Document.class).get("$lt"));
    }
}
