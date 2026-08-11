package com.ktb.chatapp.websocket.socketio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UserRoomsTest {

    @Test
    void preservesAllRoomMembershipsWhenAddsHappenConcurrently() throws Exception {
        UserRooms userRooms = new UserRooms(new LocalChatDataStore());
        int roomCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(roomCount);
        CountDownLatch ready = new CountDownLatch(roomCount);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();

        for (int index = 0; index < roomCount; index++) {
            String roomId = "room-" + index;
            tasks.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                userRooms.add("user-1", roomId);
                return null;
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (var task : tasks) {
            task.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(roomCount, userRooms.get("user-1").size());
    }
}
