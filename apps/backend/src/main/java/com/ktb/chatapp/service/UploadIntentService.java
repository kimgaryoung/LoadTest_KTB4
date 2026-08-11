package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.PresignUploadRequest;
import com.ktb.chatapp.dto.PresignUploadResponse;
import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadIntentStatus;
import com.ktb.chatapp.model.UploadPurpose;
import com.ktb.chatapp.repository.UploadIntentRepository;
import com.ktb.chatapp.storage.DirectUploadPort;
import com.ktb.chatapp.storage.PresignedUpload;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoredObjectMetadata;
import com.ktb.chatapp.storage.UploadSpec;
import com.ktb.chatapp.util.FileUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UploadIntentService {

    private final UploadIntentRepository uploadIntentRepository;
    private final ObjectProvider<DirectUploadPort> directUploadPortProvider;

    @Value("${file.direct-upload.enabled:false}")
    private boolean directUploadEnabled;

    @Value("${file.presign.put-ttl:10m}")
    private String putTtl;

    @Value("${file.pending-retention:24h}")
    private String pendingRetention;

    public PresignUploadResponse prepare(
            String ownerId,
            UploadPurpose purpose,
            PresignUploadRequest request) {
        DirectUploadPort directUploadPort = requireDirectUpload();
        FileUtil.validateFileMetadata(
                request.originalFilename(), request.contentType(), request.size());
        if (purpose == UploadPurpose.PROFILE && !request.contentType().startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        String filename = FileUtil.generateSafeFileName(request.originalFilename());
        String objectKey = purpose == UploadPurpose.PROFILE
                ? StorageKey.profile(filename)
                : StorageKey.chat(filename);
        Instant now = Instant.now();
        Duration ttl = DurationStyle.detectAndParse(putTtl);
        String intentId = UUID.randomUUID().toString();

        UploadIntent intent = UploadIntent.builder()
                .id(intentId)
                .ownerId(ownerId)
                .purpose(purpose)
                .objectKey(objectKey)
                .generatedFilename(filename)
                .originalFilename(FileUtil.normalizeOriginalFilename(request.originalFilename()))
                .contentType(request.contentType())
                .expectedSize(request.size())
                .checksumSha256(request.checksumSha256())
                .status(UploadIntentStatus.INITIATED)
                .expiresAt(now.plus(ttl))
                .createdAt(now)
                .build();
        uploadIntentRepository.save(intent);

        PresignedUpload upload = directUploadPort.presignPut(new UploadSpec(
                objectKey,
                request.contentType(),
                request.size(),
                request.checksumSha256(),
                ttl,
                Map.of("upload-intent-id", intentId, "owner-id", ownerId)));
        return new PresignUploadResponse(
                intentId,
                objectKey,
                upload.url().toString(),
                upload.method(),
                upload.headers(),
                upload.expiresAt());
    }

    public UploadIntent verify(String intentId, String ownerId, UploadPurpose purpose) {
        DirectUploadPort directUploadPort = requireDirectUpload();
        if (!StringUtils.hasText(intentId)) {
            throw new IllegalArgumentException("uploadIntentId가 필요합니다.");
        }
        UploadIntent intent = uploadIntentRepository.findByIdAndOwnerId(intentId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("업로드 요청을 찾을 수 없습니다."));
        if (intent.getPurpose() != purpose) {
            throw new IllegalArgumentException("업로드 용도가 올바르지 않습니다.");
        }
        if (intent.getStatus() == UploadIntentStatus.EXPIRED
                || intent.getStatus() == UploadIntentStatus.FAILED
                || (intent.getExpiresAt().isBefore(Instant.now())
                    && intent.getStatus() == UploadIntentStatus.INITIATED)) {
            throw new IllegalStateException("업로드 요청이 만료되었습니다.");
        }

        StoredObjectMetadata object = directUploadPort.head(intent.getObjectKey());
        if (object.size() != intent.getExpectedSize()) {
            fail(intent);
            throw new IllegalStateException("업로드된 파일 크기가 일치하지 않습니다.");
        }
        if (!intent.getContentType().equalsIgnoreCase(object.contentType())) {
            fail(intent);
            throw new IllegalStateException("업로드된 파일 형식이 일치하지 않습니다.");
        }
        if (StringUtils.hasText(intent.getChecksumSha256())
                && !intent.getChecksumSha256().equals(object.checksumSha256())) {
            fail(intent);
            throw new IllegalStateException("업로드된 파일 checksum이 일치하지 않습니다.");
        }
        if (!intentId.equals(object.metadata().get("upload-intent-id"))
                || !ownerId.equals(object.metadata().get("owner-id"))) {
            fail(intent);
            throw new IllegalStateException("업로드된 객체의 소유권 정보가 일치하지 않습니다.");
        }
        return intent;
    }

    public void markCompleted(UploadIntent intent) {
        if (intent.getStatus() == UploadIntentStatus.INITIATED) {
            intent.setStatus(UploadIntentStatus.COMPLETED);
            intent.setCompletedAt(Instant.now());
            intent.setExpiresAt(Instant.now().plus(
                    DurationStyle.detectAndParse(pendingRetention)));
            uploadIntentRepository.save(intent);
        }
    }

    public void markBound(String intentId) {
        if (!StringUtils.hasText(intentId)) {
            return;
        }
        uploadIntentRepository.findById(intentId).ifPresent(intent -> {
            intent.setStatus(UploadIntentStatus.BOUND);
            uploadIntentRepository.save(intent);
        });
    }

    private void fail(UploadIntent intent) {
        intent.setStatus(UploadIntentStatus.FAILED);
        uploadIntentRepository.save(intent);
    }

    private DirectUploadPort requireDirectUpload() {
        DirectUploadPort port = directUploadPortProvider.getIfAvailable();
        if (!directUploadEnabled || port == null) {
            throw new DirectUploadUnavailableException("직접 업로드가 비활성화되어 있습니다.");
        }
        return port;
    }
}
