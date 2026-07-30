package com.velorix.backend.exception;

public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String resource) {
        super(String.format("%s already exists", resource));
    }

    public DuplicateResourceException(String resource, String value) {
        super(String.format("%s with value '%s' already exists", resource, value));
    }
}