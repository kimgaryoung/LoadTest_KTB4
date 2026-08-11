package com.ktb.chatapp.dto;

public record PresignUploadRequest(
        String originalFilename,
        String contentType,
        long size,
        String checksumSha256) {
}
