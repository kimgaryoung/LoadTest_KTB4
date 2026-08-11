package com.ktb.chatapp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.model.Room;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(MongoTestContainer.class)
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RoomKeysetPaginationIntegrationTest {

    @Autowired private RoomRepository roomRepository;
    @Autowired private MongoTemplate mongoTemplate;

    private List<Room> expectedOrder;

    @BeforeEach
    void setUp() {
        roomRepository.deleteAll();
        LocalDateTime baseTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        List<Room> rooms = IntStream.range(0, 47)
                .mapToObj(index -> Room.builder()
                        .id(new ObjectId().toHexString())
                        .name("Room " + index)
                        .createdAt(baseTime.minusMinutes(index / 6))
                        .participantIds(new HashSet<>())
                        .build())
                .toList();
        expectedOrder = roomRepository.saveAll(rooms).stream()
                .sorted(Comparator.comparing(Room::getCreatedAt).reversed()
                        .thenComparing(Room::getId, Comparator.reverseOrder()))
                .toList();
    }

    @AfterEach
    void tearDown() {
        roomRepository.deleteAll();
    }

    @Test
    void compoundDescendingIndexIsCreated() {
        Document index = mongoTemplate.getCollection("rooms")
                .listIndexes()
                .into(new ArrayList<>())
                .stream()
                .filter(candidate -> "created_at_id_desc_idx".equals(candidate.getString("name")))
                .findFirst()
                .orElseThrow();

        assertThat(index.get("key", Document.class))
                .isEqualTo(new Document("createdAt", -1).append("_id", -1));
    }

    @Test
    void traversesSameTimestampRoomsWithoutDuplicatesOrOmissions() {
        List<String> traversedIds = new ArrayList<>();
        LocalDateTime cursorTime = null;
        String cursorId = null;
        boolean hasMore;

        do {
            List<Room> fetched = roomRepository.findPageAfter(cursorTime, cursorId, 8);
            hasMore = fetched.size() > 7;
            List<Room> page = hasMore ? fetched.subList(0, 7) : fetched;
            traversedIds.addAll(page.stream().map(Room::getId).toList());
            if (hasMore) {
                Room lastRoom = page.getLast();
                cursorTime = lastRoom.getCreatedAt();
                cursorId = lastRoom.getId();
            }
        } while (hasMore);

        assertThat(traversedIds)
                .containsExactlyElementsOf(expectedOrder.stream().map(Room::getId).toList());
        assertThat(new HashSet<>(traversedIds)).hasSize(expectedOrder.size());
    }
}
