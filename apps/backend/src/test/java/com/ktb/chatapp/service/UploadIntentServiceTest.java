package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.PresignUploadRequest;
import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadIntentStatus;
import com.ktb.chatapp.model.UploadPurpose;
import com.ktb.chatapp.repository.UploadIntentRepository;
import com.ktb.chatapp.storage.DirectUploadPort;
import com.ktb.chatapp.storage.PresignedUpload;
import com.ktb.chatapp.storage.StoredObjectMetadata;
import com.ktb.chatapp.storage.UploadSpec;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadIntentService 단위 테스트")
class UploadIntentServiceTest {

    @Mock private UploadIntentRepository repository;
    @Mock private ObjectProvider<DirectUploadPort> portProvider;
    @Mock private DirectUploadPort directUploadPort;

    private UploadIntentService service;

    @BeforeEach
    void setUp() {
        service = new UploadIntentService(repository, portProvider);
        ReflectionTestUtils.setField(service, "directUploadEnabled", true);
        ReflectionTestUtils.setField(service, "putTtl", "10m");
        ReflectionTestUtils.setField(service, "pendingRetention", "24h");
        when(portProvider.getIfAvailable()).thenReturn(directUploadPort);
    }

    @Test
    @DisplayName("준비 요청은 서버 생성 chat key와 소유권 metadata로 PUT을 서명한다")
    void prepareChatUploadUsesServerGeneratedKeyAndOwnershipMetadata() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(directUploadPort.presignPut(any())).thenReturn(new PresignedUpload(
                URI.create("https://bucket.example/upload"),
                "PUT",
                Map.of("Content-Type", List.of("image/png")),
                Instant.now().plusSeconds(600)));

        var response = service.prepare("user-1", UploadPurpose.CHAT,
                new PresignUploadRequest("photo.png", "image/png", 100, "checksum"));

        assertThat(response.uploadIntentId()).isNotBlank();
        assertThat(response.objectKey()).startsWith("chat/").endsWith(".png");
        ArgumentCaptor<UploadSpec> spec = ArgumentCaptor.forClass(UploadSpec.class);
        verify(directUploadPort).presignPut(spec.capture());
        assertThat(spec.getValue().metadata()).containsEntry("owner-id", "user-1");
        assertThat(spec.getValue().metadata().get("upload-intent-id"))
                .isEqualTo(response.uploadIntentId());
    }

    @Test
    @DisplayName("완료 검증은 크기, MIME, checksum, 소유권 metadata를 모두 대조한다")
    void verifyChecksStoredObjectMetadata() {
        UploadIntent intent = intent();
        when(repository.findByIdAndOwnerId("intent-1", "user-1"))
                .thenReturn(Optional.of(intent));
        when(directUploadPort.head("chat/photo.png")).thenReturn(new StoredObjectMetadata(
                "chat/photo.png",
                100,
                "image/png",
                "checksum",
                Map.of("upload-intent-id", "intent-1", "owner-id", "user-1")));

        assertThat(service.verify("intent-1", "user-1", UploadPurpose.CHAT))
                .isSameAs(intent);
    }

    @Test
    @DisplayName("S3 객체 크기가 다르면 intent를 FAILED로 기록하고 완료를 거부한다")
    void verifyRejectsSizeMismatch() {
        UploadIntent intent = intent();
        when(repository.findByIdAndOwnerId("intent-1", "user-1"))
                .thenReturn(Optional.of(intent));
        when(directUploadPort.head("chat/photo.png")).thenReturn(new StoredObjectMetadata(
                "chat/photo.png",
                101,
                "image/png",
                "checksum",
                Map.of("upload-intent-id", "intent-1", "owner-id", "user-1")));

        assertThatThrownBy(() -> service.verify("intent-1", "user-1", UploadPurpose.CHAT))
                .hasMessage("업로드된 파일 크기가 일치하지 않습니다.");
        assertThat(intent.getStatus()).isEqualTo(UploadIntentStatus.FAILED);
        verify(repository).save(intent);
    }

    private UploadIntent intent() {
        return UploadIntent.builder()
                .id("intent-1")
                .ownerId("user-1")
                .purpose(UploadPurpose.CHAT)
                .objectKey("chat/photo.png")
                .generatedFilename("photo.png")
                .originalFilename("photo.png")
                .contentType("image/png")
                .expectedSize(100)
                .checksumSha256("checksum")
                .status(UploadIntentStatus.INITIATED)
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
