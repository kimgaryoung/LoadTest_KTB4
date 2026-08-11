package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Room;
import java.time.LocalDateTime;
import java.util.List;

public interface RoomRepositoryCustom {

    List<Room> findPageAfter(LocalDateTime createdAt, String roomId, int size);
}
