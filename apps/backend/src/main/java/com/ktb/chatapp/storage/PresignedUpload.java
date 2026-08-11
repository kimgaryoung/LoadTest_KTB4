package com.ktb.chatapp.storage;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PresignedUpload(
        URI url,
        String method,
        Map<String, List<String>> headers,
        Instant expiresAt) {
}
