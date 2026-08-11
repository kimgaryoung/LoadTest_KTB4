package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.UploadIntent;
import com.ktb.chatapp.model.UploadIntentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UploadIntentRepository extends MongoRepository<UploadIntent, String> {
    Optional<UploadIntent> findByIdAndOwnerId(String id, String ownerId);
    List<UploadIntent> findByStatusInAndExpiresAtBefore(
            Collection<UploadIntentStatus> statuses,
            Instant expiresAt);
}
