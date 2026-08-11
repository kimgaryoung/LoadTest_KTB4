package com.ktb.chatapp.storage;

import java.time.Duration;
import java.util.Map;

public record UploadSpec(
        String key,
        String contentType,
        long size,
        String checksumSha256,
        Duration ttl,
        Map<String, String> metadata) {
}
