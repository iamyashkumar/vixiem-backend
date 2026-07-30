package com.velorix.backend.repository;

import com.velorix.backend.model.ErrorLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ErrorLogRepository extends MongoRepository<ErrorLog, String> {

    List<ErrorLog> findByEndpoint(String endpoint);

    List<ErrorLog> findByUserId(String userId);

    List<ErrorLog> findByEnabledTrue();

    List<ErrorLog> findByTimestampAfter(LocalDateTime timestamp);
}