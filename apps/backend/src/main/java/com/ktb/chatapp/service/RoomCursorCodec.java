package com.ktb.chatapp.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class RoomCursorCodec {

    private static final String SEPARATOR = "\n";

    public String encode(LocalDateTime createdAt, String roomId) {
        if (createdAt == null || roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("커서를 생성할 방의 정렬 키가 없습니다.");
        }
        String value = createdAt + SEPARATOR + roomId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public RoomCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = value.indexOf(SEPARATOR);
            if (separatorIndex <= 0 || separatorIndex == value.length() - 1
                    || value.indexOf(SEPARATOR, separatorIndex + 1) >= 0) {
                throw new IllegalArgumentException("유효하지 않은 방 목록 cursor입니다.");
            }

            LocalDateTime createdAt = LocalDateTime.parse(value.substring(0, separatorIndex));
            String roomId = value.substring(separatorIndex + 1);
            return new RoomCursor(createdAt, roomId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("유효하지 않은 방 목록 cursor입니다.", exception);
        }
    }

    public record RoomCursor(LocalDateTime createdAt, String roomId) {}
}
