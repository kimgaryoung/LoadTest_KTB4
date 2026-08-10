package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.File;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FileRepository extends MongoRepository<File, String> {
    @Query(value = "{ 'filename': ?0 }", fields = "{ '_id': 1, 'filename': 1, 'user': 1, 'mimetype': 1, 'size': 1, 'originalname': 1 }")
    Optional<File> findByFilename(String filename);
}
