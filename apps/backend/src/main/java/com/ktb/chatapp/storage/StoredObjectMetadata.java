package com.ktb.chatapp.storage;

import java.util.Map;

public record StoredObjectMetadata(
        String key,
        long size,
        String contentType,
        String checksumSha256,
        Map<String, String> metadata) {
}
