package com.ktb.chatapp.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PresignUploadResponse(
        String uploadIntentId,
        String objectKey,
        String uploadUrl,
        String method,
        Map<String, List<String>> headers,
        Instant expiresAt) {
}
