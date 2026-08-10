package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private static final long NOTIFICATION_INTERVAL_MILLIS = 1_000L;
    private final ConcurrentHashMap<String, Long> lastNotifiedAt = new ConcurrentHashMap<>();

    @Async("chatBackgroundExecutor")
    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastNotifiedAt.put(roomId, now);
        if (previous != null && now - previous < NOTIFICATION_INTERVAL_MILLIS) {
            return;
        }

        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }
}
