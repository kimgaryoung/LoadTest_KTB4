package com.ktb.chatapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RoomServicePaginationTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomService roomService;
    private List<Room> orderedRooms;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
                roomRepository,
                userRepository,
                new RoomCursorCodec(),
                passwordEncoder,
                eventPublisher);
        LocalDateTime newest = LocalDateTime.of(2026, 8, 11, 15, 0);
        orderedRooms = new ArrayList<>();
        for (int index = 0; index < 41; index++) {
            // Consecutive groups deliberately share createdAt so roomId must break ties.
            orderedRooms.add(Room.builder()
                    .id("room-" + String.format("%03d", 100 - index))
                    .name("Room " + index)
                    .createdAt(newest.minusMinutes(index / 5))
                    .participantIds(new HashSet<>())
                    .build());
        }

        when(roomRepository.findPageAfter(org.mockito.ArgumentMatchers.nullable(LocalDateTime.class),
                org.mockito.ArgumentMatchers.nullable(String.class), anyInt()))
                .thenAnswer(invocation -> {
                    LocalDateTime cursorTime = invocation.getArgument(0);
                    String cursorId = invocation.getArgument(1);
                    int size = invocation.getArgument(2);
                    int start = 0;
                    if (cursorTime != null) {
                        while (start < orderedRooms.size()) {
                            Room candidate = orderedRooms.get(start);
                            if (candidate.getCreatedAt().equals(cursorTime)
                                    && candidate.getId().equals(cursorId)) {
                                start++;
                                break;
                            }
                            start++;
                        }
                    }
                    return orderedRooms.subList(start, Math.min(start + size, orderedRooms.size()));
                });
    }

    @Test
    void traversesAllRoomsWithoutDuplicatesOrOmissions() {
        List<String> traversedIds = new ArrayList<>();
        String cursor = null;
        RoomsResponse page;

        do {
            page = roomService.getRooms("user@example.com", 20, cursor);
            traversedIds.addAll(page.getData().stream().map(RoomResponse::getId).toList());
            cursor = page.getMetadata().getNextCursor();
        } while (page.getMetadata().isHasMore());

        assertEquals(orderedRooms.stream().map(Room::getId).toList(), traversedIds);
        assertEquals(orderedRooms.size(), new HashSet<>(traversedIds).size());
        assertFalse(page.getMetadata().isHasMore());
    }

    @Test
    void firstPageReturnsOnlyLimitAndLimitPlusOneDeterminesHasMore() {
        RoomsResponse firstPage = roomService.getRooms("user@example.com", 20, null);

        assertEquals(20, firstPage.getData().size());
        assertTrue(firstPage.getMetadata().isHasMore());
        assertTrue(firstPage.getMetadata().getNextCursor() != null);
    }
}
