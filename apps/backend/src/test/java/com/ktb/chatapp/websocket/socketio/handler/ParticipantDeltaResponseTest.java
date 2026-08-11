package com.ktb.chatapp.websocket.socketio.handler;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.PARTICIPANTS_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.ParticipantDeltaResponse;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ParticipantDeltaResponseTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRooms userRooms;
    @Mock private MessageLoader messageLoader;
    @Mock private MessageResponseMapper messageResponseMapper;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    @Test
    void joinDeltaIncludesTheRoomId() {
        RoomJoinHandler handler = new RoomJoinHandler(
                socketIOServer,
                messageRepository,
                roomRepository,
                userRepository,
                userRooms,
                messageLoader,
                messageResponseMapper,
                roomLeaveHandler);
        ReflectionTestUtils.setField(handler, "participantDeltaEnabled", true);

        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-1").participantIds(Set.of("user-1")).build();
        FetchMessagesResponse messages = FetchMessagesResponse.builder().messages(List.of()).hasMore(false).build();

        when(client.get("user")).thenReturn(socketUser);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setTimestamp(LocalDateTime.now());
            return message;
        });
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1"))).thenReturn(messages);
        when(messageResponseMapper.mapToMessageResponse(any(Message.class), eq(null)))
                .thenReturn(MessageResponse.builder().type(MessageType.system).build());
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleJoinRoom(client, "room-1");

        ArgumentCaptor<ParticipantDeltaResponse> delta = ArgumentCaptor.forClass(ParticipantDeltaResponse.class);
        verify(roomOperations).sendEvent(eq(MESSAGE), any());
        verify(roomOperations).sendEvent(eq(PARTICIPANTS_UPDATE), eq(client), delta.capture());
        assertEquals("room-1", delta.getValue().getRoomId());
        assertEquals("joined", delta.getValue().getType());
        assertEquals("user-1", delta.getValue().getParticipant().getId());
    }

    @Test
    void leaveDeltaIncludesTheRoomId() {
        RoomLeaveHandler handler = new RoomLeaveHandler(
                socketIOServer,
                messageRepository,
                roomRepository,
                userRepository,
                userRooms,
                messageResponseMapper);
        ReflectionTestUtils.setField(handler, "participantDeltaEnabled", true);

        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-1").name("Room 1").participantIds(Set.of("user-1")).build();

        when(client.get("user")).thenReturn(socketUser);
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(roomOperations.getClients()).thenReturn(Set.of());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageResponseMapper.mapToMessageResponse(any(Message.class), eq(null)))
                .thenReturn(MessageResponse.builder().type(MessageType.system).build());
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleLeaveRoom(client, "room-1");

        ArgumentCaptor<ParticipantDeltaResponse> delta = ArgumentCaptor.forClass(ParticipantDeltaResponse.class);
        verify(roomOperations).sendEvent(eq(PARTICIPANTS_UPDATE), delta.capture());
        assertEquals("room-1", delta.getValue().getRoomId());
        assertEquals("left", delta.getValue().getType());
        assertEquals("user-1", delta.getValue().getParticipant().getId());
    }
}
