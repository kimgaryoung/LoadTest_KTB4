package com.ktb.chatapp.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "upload_intents")
public class UploadIntent {
    @Id
    private String id;

    @Indexed
    private String ownerId;

    private UploadPurpose purpose;

    @Indexed(unique = true)
    private String objectKey;

    private String generatedFilename;
    private String originalFilename;
    private String contentType;
    private long expectedSize;
    private String checksumSha256;

    @Indexed
    private UploadIntentStatus status;

    @Indexed
    private Instant expiresAt;

    private Instant createdAt;
    private Instant completedAt;
}
