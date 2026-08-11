package com.ktb.chatapp.service;

import com.ktb.chatapp.model.FileUploadStatus;
import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadIntentStatus;
import com.ktb.chatapp.model.UploadPurpose;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UploadIntentRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.StoragePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.direct-upload.enabled", havingValue = "true")
public class UploadIntentCleanupService {

    private final UploadIntentRepository uploadIntentRepository;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final StoragePort storagePort;
    private final MeterRegistry meterRegistry;

    @Scheduled(
            fixedDelayString = "${file.cleanup.interval:10m}",
            initialDelayString = "${file.cleanup.initial-delay:10m}")
    public void cleanupExpiredUploads() {
        List<UploadIntent> expired = uploadIntentRepository.findByStatusInAndExpiresAtBefore(
                List.of(UploadIntentStatus.INITIATED, UploadIntentStatus.COMPLETED),
                Instant.now());
        for (UploadIntent intent : expired) {
            cleanup(intent);
        }
    }

    private void cleanup(UploadIntent intent) {
        try {
            if (isReferenced(intent)) {
                repairBoundState(intent);
                counter("repaired").increment();
                return;
            }

            storagePort.delete(intent.getObjectKey());
            fileRepository.findByUploadIntentId(intent.getId()).ifPresent(fileRepository::delete);
            intent.setStatus(UploadIntentStatus.EXPIRED);
            uploadIntentRepository.save(intent);
            counter("deleted").increment();
        } catch (RuntimeException exception) {
            counter("failed").increment();
            log.warn("만료 upload intent 정리 실패: intentId={}, key={}",
                    intent.getId(), intent.getObjectKey(), exception);
        }
    }

    private boolean isReferenced(UploadIntent intent) {
        if (intent.getPurpose() == UploadPurpose.PROFILE) {
            return userRepository.existsByProfileImage(intent.getObjectKey());
        }
        return fileRepository.findByUploadIntentId(intent.getId())
                .flatMap(file -> messageRepository.findByFileId(file.getId()))
                .isPresent();
    }

    private void repairBoundState(UploadIntent intent) {
        fileRepository.findByUploadIntentId(intent.getId()).ifPresent(file -> {
            file.setUploadStatus(FileUploadStatus.BOUND);
            fileRepository.save(file);
        });
        intent.setStatus(UploadIntentStatus.BOUND);
        uploadIntentRepository.save(intent);
    }

    private Counter counter(String result) {
        return Counter.builder("file.upload.cleanup.total")
                .tag("result", result)
                .register(meterRegistry);
    }
}
