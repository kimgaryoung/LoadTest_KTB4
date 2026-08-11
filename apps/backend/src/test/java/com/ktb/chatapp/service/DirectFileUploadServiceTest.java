package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.FileUploadStatus;
import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadPurpose;
import com.ktb.chatapp.repository.FileRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DirectFileUploadServiceTest {

    @Mock private UploadIntentService uploadIntentService;
    @Mock private FileRepository fileRepository;

    @Test
    void completeCreatesPendingFileFromVerifiedIntent() {
        DirectFileUploadService service = new DirectFileUploadService(
                uploadIntentService, fileRepository);
        UploadIntent intent = UploadIntent.builder()
                .id("intent-1")
                .ownerId("user-1")
                .objectKey("chat/generated.png")
                .generatedFilename("generated.png")
                .originalFilename("원본.png")
                .contentType("image/png")
                .expectedSize(100)
                .build();
        when(fileRepository.findByUploadIntentId("intent-1")).thenReturn(Optional.empty());
        when(uploadIntentService.verify("intent-1", "user-1", UploadPurpose.CHAT))
                .thenReturn(intent);
        when(fileRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    File file = invocation.getArgument(0);
                    file.setId("file-1");
                    return file;
                });

        File completed = service.complete("intent-1", "user-1");

        assertThat(completed.getId()).isEqualTo("file-1");
        assertThat(completed.getPath()).isEqualTo("chat/generated.png");
        assertThat(completed.getUploadStatus()).isEqualTo(FileUploadStatus.PENDING);
        verify(uploadIntentService).markCompleted(intent);
    }

    @Test
    void markBoundUpdatesFileAndIntent() {
        DirectFileUploadService service = new DirectFileUploadService(
                uploadIntentService, fileRepository);
        File file = File.builder()
                .id("file-1")
                .uploadIntentId("intent-1")
                .uploadStatus(FileUploadStatus.PENDING)
                .build();
        when(fileRepository.findById("file-1")).thenReturn(Optional.of(file));

        service.markBound("file-1");

        assertThat(file.getUploadStatus()).isEqualTo(FileUploadStatus.BOUND);
        verify(fileRepository).save(file);
        verify(uploadIntentService).markBound("intent-1");
    }

    @Test
    void completeReturnsConcurrentResultWhenUniqueIntentInsertLosesRace() {
        DirectFileUploadService service = new DirectFileUploadService(
                uploadIntentService, fileRepository);
        UploadIntent intent = UploadIntent.builder()
                .id("intent-1")
                .ownerId("user-1")
                .objectKey("chat/generated.png")
                .generatedFilename("generated.png")
                .originalFilename("original.png")
                .contentType("image/png")
                .expectedSize(100)
                .build();
        File concurrentResult = File.builder()
                .id("file-existing")
                .uploadIntentId("intent-1")
                .user("user-1")
                .build();
        when(fileRepository.findByUploadIntentId("intent-1"))
                .thenReturn(Optional.empty(), Optional.of(concurrentResult));
        when(uploadIntentService.verify("intent-1", "user-1", UploadPurpose.CHAT))
                .thenReturn(intent);
        when(fileRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DuplicateKeyException("duplicate uploadIntentId"));

        File completed = service.complete("intent-1", "user-1");

        assertThat(completed).isSameAs(concurrentResult);
    }
}
