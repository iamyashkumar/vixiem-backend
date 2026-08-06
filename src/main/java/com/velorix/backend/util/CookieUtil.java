package com.velorix.backend.util;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class CookieUtil {

    @Value("${jwt.access-token.expiration.ms:900000}")
    private long jwtExpiration;

    @Value("${jwt.refresh-token.expiration.ms:604800000}")
    private long refreshExpiration;

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    @Value("${app.cookie.same-site:None}")
    private String sameSite;

    public HttpCookie createAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .maxAge(jwtExpiration / 1000)
                .httpOnly(true)
                .secure(secureCookies)
                .path("/")
                .sameSite(sameSite)
                .build();
    }

    public HttpCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .maxAge(refreshExpiration / 1000)
                .httpOnly(true)
                .secure(secureCookies)
                .path("/")
                .sameSite(sameSite)
                .build();
    }

    public HttpCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(secureCookies)
                .path(path)
                .sameSite(sameSite)
                .build();
    }
}
