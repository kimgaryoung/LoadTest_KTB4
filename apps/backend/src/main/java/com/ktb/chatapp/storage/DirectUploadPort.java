package com.ktb.chatapp.storage;

public interface DirectUploadPort {
    PresignedUpload presignPut(UploadSpec spec);
    StoredObjectMetadata head(String key);
}
