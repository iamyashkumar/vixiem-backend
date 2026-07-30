package com.velorix.backend.util;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class CookieUtil {

    @Value("${jwt.expiration:900000}")
    private long jwtExpiration;

    @Value("${jwt.refreshExpiration:604800000}")
    private long refreshExpiration;

    public HttpCookie createAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .maxAge(jwtExpiration / 1000)
                .httpOnly(true)
                .secure(false) // Set to true in production with HTTPS
                .path("/")
                .sameSite("Lax")
                .build();
    }

    public HttpCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .maxAge(refreshExpiration / 1000)
                .httpOnly(true)
                .secure(false) // Set to true in production with HTTPS
                .path("/api/auth/refresh")
                .sameSite("Strict")
                .build();
    }

    public HttpCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(false)
                .path(path)
                .build();
    }
}
