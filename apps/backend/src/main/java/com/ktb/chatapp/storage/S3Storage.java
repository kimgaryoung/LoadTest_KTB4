package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort, DirectUploadPort {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3Storage(
            S3Client s3Client,
            S3Presigner presigner,
            @Value("${app.s3.bucket}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("FILE_STORAGE_TYPE=s3이면 S3_BUCKET이 필요합니다.");
        }
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        // S3 reads must be redirected with a bounded presigned URL. Never buffer an object in WAS.
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public Optional<URI> offloadUrl(
            String key,
            java.time.Duration ttl,
            ContentDisposition disposition) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(disposition.toString())
                .build();
        var request = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(objectRequest)
                .build());
        return Optional.of(URI.create(request.url().toExternalForm()));
    }

    @Override
    public PresignedUpload presignPut(UploadSpec spec) {
        PutObjectRequest.Builder objectBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(spec.key())
                .contentType(spec.contentType())
                .metadata(spec.metadata());
        if (spec.checksumSha256() != null && !spec.checksumSha256().isBlank()) {
            objectBuilder.checksumSHA256(spec.checksumSha256());
        }

        var request = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(spec.ttl())
                .putObjectRequest(objectBuilder.build())
                .build());
        return new PresignedUpload(
                URI.create(request.url().toExternalForm()),
                request.httpRequest().method().name(),
                request.httpRequest().headers(),
                Instant.now().plus(spec.ttl()));
    }

    @Override
    public StoredObjectMetadata head(String key) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return new StoredObjectMetadata(
                    key,
                    response.contentLength(),
                    response.contentType(),
                    response.checksumSHA256(),
                    response.metadata());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new IllegalStateException("업로드된 객체를 찾을 수 없습니다.", exception);
            }
            throw exception;
        }
    }
}
