package com.velorix.backend.exception;

import com.velorix.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ✅ Validation errors handle करो
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> {
                errors.put(error.getField(), error.getDefaultMessage());
                System.out.println("VALIDATION ERROR: Field '" + error.getField() + "' -> " + error.getDefaultMessage());
        });

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(400)
                .message("Validation failed")
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .errors(errors)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    // ✅ Custom exceptions handle करो
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(404)
                .message(ex.getMessage())
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .error("NOT_FOUND")
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
            InvalidCredentialsException ex,
            HttpServletRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(401)
                .message(ex.getMessage())
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .error("INVALID_CREDENTIALS")
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResourceException(
            DuplicateResourceException ex,
            HttpServletRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(409)
                .message(ex.getMessage())
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .error("DUPLICATE_RESOURCE")
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerifiedException(
            EmailNotVerifiedException ex,
            HttpServletRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(403)
                .message(ex.getMessage())
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .error("EMAIL_NOT_VERIFIED")
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AiNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleAiNotConfiguredException(AiNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of("error", "AI service is not configured.", "code", "AI_NOT_CONFIGURED")
        );
    }

    // ✅ Generic exceptions handle करो
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(
        Exception ex,
        HttpServletRequest request) {
        
        ex.printStackTrace();

        String errorMsg = ex.getMessage();
        if (errorMsg == null || errorMsg.trim().isEmpty()) {
            errorMsg = ex.getCause() != null ? ex.getCause().toString() : ex.toString();
        }

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(500)
                .message(errorMsg)
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .error("INTERNAL_SERVER_ERROR")
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}