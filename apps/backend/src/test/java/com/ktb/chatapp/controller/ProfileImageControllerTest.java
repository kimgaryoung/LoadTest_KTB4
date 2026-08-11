package com.ktb.chatapp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.storage.StoragePort;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProfileImageControllerTest {

    @Mock private StoragePort storagePort;
    private ProfileImageController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfileImageController(storagePort);
        ReflectionTestUtils.setField(controller, "presignTtl", Duration.ofMinutes(5));
    }

    @Test
    void s3ProfileImageKeepsApiUrlAndRedirectsToBoundedGetUrl() {
        URI signedUrl = URI.create("https://bucket.example/profiles/avatar.png?signature=test");
        when(storagePort.offloadUrl(
                eq("profiles/avatar.png"),
                eq(Duration.ofMinutes(5)),
                any(ContentDisposition.class)))
                .thenReturn(Optional.of(signedUrl));

        var response = controller.getProfileImage("avatar.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isEqualTo(signedUrl);
        verify(storagePort, never()).open(any());
    }

    @Test
    void pathTraversalIsRejectedBeforeStorageAccess() {
        var response = controller.getProfileImage("../secret.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storagePort, never()).offloadUrl(any(), any(), any());
    }
}
