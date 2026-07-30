package com.velorix.backend.repository;

import com.velorix.backend.model.ApiEndpoint;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ApiEndpointRepository extends MongoRepository<ApiEndpoint, String> {
    List<ApiEndpoint> findByUserId(String userId);
    List<ApiEndpoint> findByUserIdAndIsActiveTrue(String userId);
    java.util.Optional<ApiEndpoint> findByIdAndUserId(String id, String userId);
    void deleteByUserId(String userId);
}