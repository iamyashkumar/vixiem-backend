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

    @Value("${app.is-production:true}")
    private boolean isProduction;

    public HttpCookie createAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .maxAge(jwtExpiration / 1000)
                .httpOnly(true)
                .secure(isProduction)
                .path("/")
                .sameSite(isProduction ? "None" : "Lax")
                .build();
    }

    public HttpCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .maxAge(refreshExpiration / 1000)
                .httpOnly(true)
                .secure(isProduction)
                .path("/")
                .sameSite(isProduction ? "None" : "Lax")
                .build();
    }

    public HttpCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(isProduction)
                .path(path)
                .sameSite(isProduction ? "None" : "Lax")
                .build();
    }
}
