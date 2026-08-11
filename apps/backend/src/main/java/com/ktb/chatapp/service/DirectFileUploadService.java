package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.FileUploadStatus;
import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadPurpose;
import com.ktb.chatapp.repository.FileRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DirectFileUploadService {

    private final UploadIntentService uploadIntentService;
    private final FileRepository fileRepository;

    public File complete(String intentId, String ownerId) {
        return fileRepository.findByUploadIntentId(intentId)
                .filter(file -> ownerId.equals(file.getUser()))
                .orElseGet(() -> createFileIdempotently(intentId, ownerId));
    }

    public void markBound(String fileId) {
        fileRepository.findById(fileId).ifPresent(file -> {
            if (file.getUploadStatus() == FileUploadStatus.PENDING) {
                file.setUploadStatus(FileUploadStatus.BOUND);
                fileRepository.save(file);
            }
            uploadIntentService.markBound(file.getUploadIntentId());
        });
    }

    private File createFile(String intentId, String ownerId) {
        UploadIntent intent = uploadIntentService.verify(intentId, ownerId, UploadPurpose.CHAT);
        File file = File.builder()
                .filename(intent.getGeneratedFilename())
                .originalname(intent.getOriginalFilename())
                .mimetype(intent.getContentType())
                .size(intent.getExpectedSize())
                .path(intent.getObjectKey())
                .uploadIntentId(intent.getId())
                .uploadStatus(FileUploadStatus.PENDING)
                .user(ownerId)
                .uploadDate(LocalDateTime.now())
                .build();
        File saved = fileRepository.save(file);
        uploadIntentService.markCompleted(intent);
        return saved;
    }

    private File createFileIdempotently(String intentId, String ownerId) {
        try {
            return createFile(intentId, ownerId);
        } catch (DuplicateKeyException exception) {
            return fileRepository.findByUploadIntentId(intentId)
                    .filter(file -> ownerId.equals(file.getUser()))
                    .orElseThrow(() -> exception);
        }
    }
}
