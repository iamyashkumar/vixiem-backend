package com.velorix.backend.repository;

import com.velorix.backend.model.Workspace;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceRepository extends MongoRepository<Workspace, String> {
    List<Workspace> findByUserIdIn(List<String> userIds);
    List<Workspace> findByUserId(String userId);
    Optional<Workspace> findByIdAndUserIdIn(String id, List<String> userIds);
    Optional<Workspace> findByUserIdAndIsDefaultTrue(String userId);
    long countByUserIdIn(List<String> userIds);
}
