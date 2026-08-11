package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Room;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@RequiredArgsConstructor
public class RoomRepositoryCustomImpl implements RoomRepositoryCustom {

    private static final Sort KEYSET_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("_id"));

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Room> findPageAfter(LocalDateTime createdAt, String roomId, int size) {
        Query query = new Query();
        if (createdAt != null && roomId != null) {
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("createdAt").lt(createdAt),
                    new Criteria().andOperator(
                            Criteria.where("createdAt").is(createdAt),
                            Criteria.where("_id").lt(roomId))));
        }

        query.with(KEYSET_SORT).limit(size);
        return mongoTemplate.find(query, Room.class);
    }
}
