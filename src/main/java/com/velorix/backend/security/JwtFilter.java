package com.velorix.backend.security;

import com.velorix.backend.service.CustomUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import jakarta.servlet.http.Cookie;

@Component
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = extractToken(request);

            if (token != null) {
                String email = jwtUtil.validateAndExtract(token);

                if (email != null && jwtUtil.isAccessToken(token)
                        && SecurityContextHolder.getContext().getAuthentication() == null && !jwtUtil.isTokenExpired(token)) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                    
                    boolean tokenValid = true;
                    if (userDetails instanceof CustomUserDetails) {
                        CustomUserDetails customUser = (CustomUserDetails) userDetails;
                        if (customUser.getUser().getLastPasswordResetDate() != null) {
                            java.util.Date issuedAt = jwtUtil.getIssuedAt(token);
                            if (issuedAt != null && issuedAt.before(customUser.getUser().getLastPasswordResetDate())) {
                                tokenValid = false;
                                log.warn("Token revoked due to password change for user: {}", email);
                            }
                        }
                    }

                    if (tokenValid) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities()
                                );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        log.debug("User authenticated: {}", email);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("JWT validation failed (ignoring for public endpoints): {}", e.getMessage());
            SecurityContextHolder.clearContext();
            // Do NOT return 401 here. Let the filter chain continue.
            // If the endpoint is public, it will succeed. 
            // If it is protected, Spring Security will throw 401 automatically.
        }

        filterChain.doFilter(request, response);
    }

    // ✅ Extract token from Cookies or Authorization header
    private String extractToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
