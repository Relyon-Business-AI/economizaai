package com.relyon.economizaai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

@Slf4j
@Service
public class JwtService {

    /**
     * The fallback committed in application.yaml. Anyone with repo access can
     * mint tokens signed with it, so it must never survive outside local dev.
     */
    static final String DEV_FALLBACK_SECRET =
            "economizai-dev-secret-key-must-be-at-least-256-bits-long-for-hs256";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters");
        }
        // Fail-fast guard: a prod-shaped deploy that forgot JWT_SECRET would
        // otherwise boot happily with a publicly-known signing key.
        if (DEV_FALLBACK_SECRET.equals(secret) && !"dev".equals(activeProfile)) {
            throw new IllegalStateException(
                    "JWT secret is the committed dev fallback but the active profile is '"
                            + activeProfile + "' — set JWT_SECRET");
        }
    }

    public String generateToken(UserDetails userDetails) {
        var now = new Date();
        var expiryDate = new Date(now.getTime() + expiration);
        log.debug("Generating JWT for user: {}", userDetails.getUsername());
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            var username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException ex) {
            log.debug("Token validation failed: {}", ex.getMessage());
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        var claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
}
