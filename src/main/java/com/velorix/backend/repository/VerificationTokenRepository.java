package com.velorix.backend.repository;

import com.velorix.backend.model.VerificationToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends MongoRepository<VerificationToken, String> {
    Optional<VerificationToken> findByToken(String token);
    long deleteByUserEmail(String userEmail);
    Optional<VerificationToken> findByUserEmail(String userEmail);
}
