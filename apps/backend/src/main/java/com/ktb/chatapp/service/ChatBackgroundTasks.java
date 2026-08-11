package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes optional chat side effects outside the Socket.IO event loop. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ChatBackgroundTasks {

    private final AiService aiService;

    @Async("chatBackgroundExecutor")
    public void handleAiMentions(String roomId, String userId, MessageContent content) {
        try {
            aiService.handleAIMentions(roomId, userId, content);
        } catch (Exception exception) {
            log.error("AI mention background task failed: roomId={}, userId={}", roomId, userId, exception);
        }
    }
}
