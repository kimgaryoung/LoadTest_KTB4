package com.ktb.chatapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RoomCursorCodecTest {

    private final RoomCursorCodec codec = new RoomCursorCodec();

    @Test
    void roundTripsBothKeysetSortFields() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 11, 12, 34, 56, 123_000_000);

        RoomCursorCodec.RoomCursor decoded = codec.decode(codec.encode(createdAt, "room-019"));

        assertEquals(createdAt, decoded.createdAt());
        assertEquals("room-019", decoded.roomId());
    }

    @Test
    void rejectsMalformedCursor() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not-a-valid-cursor"));
    }
}
