package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.ParticipantDeltaResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    static final String ROOM_RESTORE_IN_PROGRESS = "roomRestoreInProgress";

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;

    @Value("${socketio.participant-delta.enabled:false}")
    private boolean participantDeltaEnabled;
    
    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (Boolean.TRUE.equals(client.get(ROOM_RESTORE_IN_PROGRESS))) {
                client.joinRoom(roomId);
                return;
            }
            
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }
            
            if (roomRepository.findById(roomId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }
            
            // 이미 해당 방에 참여 중인지 확인
            if (userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} already in room {}", userId, roomId);
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, buildJoinRoomSuccessResponse(roomId, userId));
                return;
            }

            roomRepository.addParticipant(roomId, userId);

            // Join socket room and add to user's room set
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            Message joinMessage = Message.builder()
                .roomId(roomId)
                .content(userName + "님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(LocalDateTime.now())
                .mentions(new ArrayList<>())
                .reactions(new HashMap<>())
                .readers(new ArrayList<>())
                .metadata(new HashMap<>())
                .build();

            joinMessage = messageRepository.save(joinMessage);

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            // 업데이트된 room 다시 조회하여 최신 participantIds 가져오기
            Optional<Room> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 참가자 정보 조회
            List<UserResponse> participants = userRepository.findAllById(roomOpt.get().getParticipantIds())
                    .stream()
                    .map(UserResponse::from)
                    .toList();
            
            JoinRoomSuccessResponse response = buildJoinRoomSuccessResponse(
                roomId,
                participants,
                messageLoadResult
            );

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 입장 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

            if (participantDeltaEnabled) {
                // 전체 목록은 입장한 사용자에게 보낸 joinRoomSuccess로만 전달한다.
                // 기존 참여자에게는 새로 입장한 사용자 1명만 delta로 알린다.
                socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, client, ParticipantDeltaResponse.builder()
                        .roomId(roomId)
                        .type("joined")
                        .participant(UserResponse.from(user))
                        .build());
            } else {
                // 테스트 및 단계적 롤백을 위한 기존 전파 방식.
                socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, participants);
            }

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
    }
    
    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }

    private JoinRoomSuccessResponse buildJoinRoomSuccessResponse(String roomId, String userId) {
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("채팅방을 찾을 수 없습니다."));

        List<UserResponse> participants = userRepository.findAllById(room.getParticipantIds())
            .stream()
            .map(UserResponse::from)
            .toList();

        return buildJoinRoomSuccessResponse(roomId, participants, messageLoadResult);
    }

    private JoinRoomSuccessResponse buildJoinRoomSuccessResponse(
        String roomId,
        List<UserResponse> participants,
        FetchMessagesResponse messageLoadResult
    ) {
        return JoinRoomSuccessResponse.builder()
            .roomId(roomId)
            .participants(participants)
            .messages(messageLoadResult.getMessages())
            .hasMore(messageLoadResult.isHasMore())
            .activeStreams(Collections.emptyList())
            .build();
    }
}
