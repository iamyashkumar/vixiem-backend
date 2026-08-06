package com.velorix.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import jakarta.annotation.PostConstruct;
import io.jsonwebtoken.Claims;


@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token.expiration.ms}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token.expiration.ms}")
    private long refreshTokenExpiration;

    @PostConstruct
    private void validateSigningSecret() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 64) {
            throw new IllegalStateException("JWT_SECRET must be set to a cryptographically random value of at least 64 bytes");
        }
    }

    // ✅ Get signing key
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    // ✅ Generate Access Token (15 minutes)
    public String generateAccessToken(String email) {
        try {
            return Jwts.builder()
                    .setSubject(email)
                    .claim("token_type", "access")
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                    .signWith(getSigningKey(), SignatureAlgorithm.HS512)
                    .compact();
        } catch (Exception e) {
            log.error("Error generating access token: {}", e.getMessage());
            throw new RuntimeException("Could not generate access token", e);
        }
    }

    // ✅ Generate Refresh Token (7 days)
    public String generateRefreshToken(String email) {
        try {
            return Jwts.builder()
                    .setSubject(email)
                    .claim("token_type", "refresh")
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
                    .signWith(getSigningKey(), SignatureAlgorithm.HS512)
                    .compact();
        } catch (Exception e) {
            log.error("Error generating refresh token: {}", e.getMessage());
            throw new RuntimeException("Could not generate refresh token", e);
        }
    }

    // ✅ Validate and Extract email from token
    public String validateAndExtract(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (ExpiredJwtException e) {
            log.warn("Token is expired: {}", e.getMessage());
            throw new RuntimeException("Token has expired", e);
        } catch (MalformedJwtException e) {
            log.warn("Invalid token: {}", e.getMessage());
            throw new RuntimeException("Invalid token", e);
        } catch (Exception e) {
            log.error("Token validation error: {}", e.getMessage());
            throw new RuntimeException("Token validation failed", e);
        }
    }

    public boolean isAccessToken(String token) {
        return "access".equals(getTokenType(token));
    }

    // ✅ Check if token is expired
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getExpiration();
            return expiration.before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            log.error("Error checking token expiration: {}", e.getMessage());
            return true;
        }
    }

    // ✅ Get expiration time
    public long getExpirationTime(String token) {
        try {
            Date expiration = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getExpiration();
            return expiration.getTime() - System.currentTimeMillis();
        } catch (Exception e) {
            log.error("Error getting expiration time: {}", e.getMessage());
            return 0;
        }
    }

    // ✅ Get all claims from token
    public Claims getAllClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.error("Error getting claims from token: {}", e.getMessage());
            return null;
        }
    }

    // ✅ Get email from token (legacy method)
    public String getUserIdFromToken(String token) {
        try {
            return validateAndExtract(token);
        } catch (Exception e) {
            log.error("Error getting user ID from token: {}", e.getMessage());
            return null;
        }
    }

    // ✅ Get user ID from request
    public String getUserIdFromRequest(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return validateAndExtract(token);
        }
        return null;
    }

    // ✅ Get token type
    public String getTokenType(String token) {
        try {
            Claims claims = getAllClaims(token);
            if (claims != null && claims.containsKey("token_type")) {
                return claims.get("token_type", String.class);
            }
            return null;
        } catch (Exception e) {
            log.error("Error getting token type: {}", e.getMessage());
            return null;
        }
    }

    // ✅ Get issued at time
    public Date getIssuedAt(String token) {
        try {
            Claims claims = getAllClaims(token);
            if (claims != null) {
                return claims.getIssuedAt();
            }
            return null;
        } catch (Exception e) {
            log.error("Error getting issued at time: {}", e.getMessage());
            return null;
        }
    }
}
