package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.cache.CachedUserProfile;
import com.ktb.chatapp.service.cache.UserProfileCache;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RoomCursorCodec roomCursorCodec;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final UserProfileCache userProfileCache;

    public RoomsResponse getRooms(String name, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit은 1 이상 100 이하여야 합니다.");
        }

        RoomCursorCodec.RoomCursor decodedCursor = roomCursorCodec.decode(cursor);
        LocalDateTime cursorCreatedAt = decodedCursor == null ? null : decodedCursor.createdAt();
        String cursorRoomId = decodedCursor == null ? null : decodedCursor.roomId();

        List<Room> fetchedRooms = roomRepository.findPageAfter(
                cursorCreatedAt, cursorRoomId, limit + 1);
        boolean hasMore = fetchedRooms.size() > limit;
        List<Room> rooms = hasMore
                ? fetchedRooms.subList(0, limit)
                : fetchedRooms;

        Map<String, CachedUserProfile> usersById = findUsersByIds(rooms.stream()
            .flatMap(room -> java.util.stream.Stream.concat(
                java.util.stream.Stream.ofNullable(room.getCreator()),
                room.getParticipantIds() == null
                    ? java.util.stream.Stream.empty()
                    : room.getParticipantIds().stream()))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet()));
        List<RoomResponse> roomResponses = rooms.stream()
            .map(room -> mapToRoomResponse(room, name, usersById))
            .toList();

        String nextCursor = null;
        if (hasMore && !rooms.isEmpty()) {
            Room lastRoom = rooms.getLast();
            nextCursor = roomCursorCodec.encode(lastRoom.getCreatedAt(), lastRoom.getId());
        }

        PageMetadata metadata = PageMetadata.builder()
            .pageSize(limit)
            .hasMore(hasMore)
            .currentCount(roomResponses.size())
            .nextCursor(nextCursor)
            .sort(PageMetadata.SortInfo.builder()
                    .field("createdAt,_id")
                    .order("desc")
                    .build())
            .build();

        return RoomsResponse.builder()
            .success(true)
            .data(roomResponses)
            .metadata(metadata)
            .build();
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        
        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(
                savedRoom, name, findUsersByIds(java.util.Set.of(creator.getId())));
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }
        
        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        
        // Publish event for room updated
        try {
            Map<String, CachedUserProfile> usersById = findUsersByIds(java.util.stream.Stream.concat(
                    java.util.stream.Stream.ofNullable(room.getCreator()),
                    room.getParticipantIds() == null
                        ? java.util.stream.Stream.empty()
                        : room.getParticipantIds().stream())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
            RoomResponse roomResponse = mapToRoomResponse(room, name, usersById);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    private RoomResponse mapToRoomResponse(
            Room room, String name, Map<String, CachedUserProfile> usersById) {
        if (room == null) return null;

        CachedUserProfile creator = room.getCreator() == null ? null : usersById.get(room.getCreator());

        List<CachedUserProfile> participants = room.getParticipantIds() == null ? List.of() : room.getParticipantIds().stream()
            .map(usersById::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        return RoomResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .creator(creator != null ? UserResponse.builder()
                .id(creator.id())
                .name(creator.name() != null ? creator.name() : "알 수 없음")
                .email(creator.email() != null ? creator.email() : "")
                .build() : null)
            .participants(participants.stream()
                .filter(p -> p != null && p.id() != null)
                .map(p -> UserResponse.builder()
                    .id(p.id())
                    .name(p.name() != null ? p.name() : "알 수 없음")
                    .email(p.email() != null ? p.email() : "")
                    .build())
                .collect(Collectors.toList()))
            .createdAtDateTime(room.getCreatedAt())
            .isCreator(creator != null && creator.id().equals(name))
            .build();
    }

    private Map<String, CachedUserProfile> findUsersByIds(java.util.Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CachedUserProfile> usersById = new LinkedHashMap<>(userProfileCache.getAll(userIds));
        java.util.Set<String> missingUserIds = userIds.stream()
                .filter(userId -> !usersById.containsKey(userId))
                .collect(Collectors.toSet());
        if (missingUserIds.isEmpty()) {
            return usersById;
        }
        List<User> loadedUsers = new java.util.ArrayList<>();
        userRepository.findAllById(missingUserIds).forEach(user -> {
            if (user != null && user.getId() != null) {
                loadedUsers.add(user);
                usersById.put(user.getId(), CachedUserProfile.from(user));
            }
        });
        userProfileCache.putAll(loadedUsers);
        return usersById;
    }
}
