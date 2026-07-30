package com.velorix.backend.repository;

import com.velorix.backend.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    // ✅ Find by email
    Optional<User> findByEmail(String email);

    // ✅ Find by username
    Optional<User> findByUsername(String username);

    // ✅ Check if email exists
    boolean existsByEmail(String email);

    // ✅ Check if username exists
    boolean existsByUsername(String username);

    // ✅ Find by enabled status
    java.util.List<User> findByEnabled(boolean enabled);

    // ✅ Count total users
    long count();
}