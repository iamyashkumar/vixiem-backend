package com.velorix.backend.repository;

import com.velorix.backend.model.AuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {
}
