package com.velorix.backend.controller;

import com.velorix.backend.dto.LoginRequest;
import com.velorix.backend.dto.RegisterRequest;
import com.velorix.backend.dto.AuthResponse;
import com.velorix.backend.dto.RefreshTokenRequest;
import com.velorix.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpCookie;
import com.velorix.backend.util.CookieUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private CookieUtil cookieUtil;

    // ✅ Register endpoint with validation
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return buildCookieResponse(response, HttpStatus.CREATED);
    }

    // ✅ Login endpoint with validation
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return buildCookieResponse(response, HttpStatus.OK);
    }

    // ✅ Google Login endpoint
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> loginWithGoogle(@Valid @RequestBody com.velorix.backend.dto.GoogleLoginRequest request) {
        AuthResponse response = authService.loginWithGoogle(request.getCredential());
        return buildCookieResponse(response, HttpStatus.OK);
    }

    // ✅ Health check endpoint
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Pong! Backend is running.");
    }

    // ✅ CSRF priming endpoint
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken(); // Force the token to be generated and cookie to be written
        }
        return ResponseEntity.ok().build();
    }

    // ✅ Current User (Hydration)
    @GetMapping("/me")
    public ResponseEntity<AuthResponse.UserDto> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String email = auth.getName();
        AuthResponse.UserDto user = authService.getUserByEmail(email);
        return ResponseEntity.ok(user);
    }

    // ✅ Refresh Token endpoint
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefresh_token(refreshToken);
        AuthResponse response = authService.refresh(request);
        return buildCookieResponse(response, HttpStatus.OK);
    }

    // ✅ Logout endpoint
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "refresh_token", required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isEmpty()) {
            authService.revokeRefreshToken(refreshToken);
        }
        HttpCookie accessCookie = cookieUtil.clearCookie("access_token", "/");
        HttpCookie refreshCookie = cookieUtil.clearCookie("refresh_token", "/api/auth/refresh");
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .build();
    }

    // ✅ Change Password endpoint
    @PutMapping("/profile/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody com.velorix.backend.dto.PasswordChangeRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            authService.changePassword(auth.getName(), request);
            
            // Log out user since tokens are revoked
            HttpCookie accessCookie = cookieUtil.clearCookie("access_token", "/");
            HttpCookie refreshCookie = cookieUtil.clearCookie("refresh_token", "/api/auth/refresh");
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(java.util.Map.of("message", "Password changed successfully, please login again"));
        } catch (com.velorix.backend.exception.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of("error", "Invalid current password"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("error", e.getMessage()));
        }
    }
    // ✅ Update Username endpoint
    @PutMapping("/profile/username")
    public ResponseEntity<?> updateUsername(@RequestBody java.util.Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String newUsername = body.get("username");
            AuthResponse.UserDto updatedUser = authService.updateUsername(auth.getName(), newUsername);
            return ResponseEntity.ok(updatedUser);
        } catch (com.velorix.backend.exception.DuplicateResourceException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("error", "Username is already taken. Please choose another username."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ✅ Delete Account endpoint
    @DeleteMapping("/profile")
    public ResponseEntity<?> deleteAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            authService.deleteAccount(auth.getName());
            
            // Clear cookies
            HttpCookie accessCookie = cookieUtil.clearCookie("access_token", "/");
            HttpCookie refreshCookie = cookieUtil.clearCookie("refresh_token", "/api/auth/refresh");
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(java.util.Map.of("message", "Account deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ✅ Verify Email endpoint
    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            authService.verifyEmail(token);
            return ResponseEntity.ok(java.util.Map.of("message", "Email verified successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ✅ Resend Verification Email
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody java.util.Map<String, String> body) {
        try {
            String email = body.get("email");
            if (email == null || email.isEmpty()) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "Email is required"));
            }
            authService.resendVerificationEmail(email);
            return ResponseEntity.ok(java.util.Map.of("message", "Verification email sent"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<AuthResponse> buildCookieResponse(AuthResponse response, HttpStatus status) {
        HttpCookie accessCookie = cookieUtil.createAccessTokenCookie(response.getAccessToken());
        HttpCookie refreshCookie = cookieUtil.createRefreshTokenCookie(response.getRefreshToken());
        
        // Strip tokens from body for security
        response.setAccessToken(null);
        response.setRefreshToken(null);
        
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }
}