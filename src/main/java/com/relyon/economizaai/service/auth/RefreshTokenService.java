package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.RefreshToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RefreshTokenRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Single-use refresh-token rotation. {@link #issue} mints a fresh token at
 * login/register; {@link #rotate} consumes one and issues the next. The old
 * token is marked {@code consumed_at} so reuse is detectable. Explicit
 * logout sets {@code revoked_at}.
 *
 * <p>Only the SHA-256 hex of the token is stored — a DB dump must not yield
 * usable 30-day sessions. A deterministic hash keeps lookup an index hit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokenRepository;
    private final SecureRandom random = new SecureRandom();

    @Value("${jwt.refresh-expiration:2592000000}")
    private long refreshExpirationMs;

    @Transactional
    public String issue(User user) {
        var token = generateToken();
        tokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(CodeHasher.sha256(token))
                .expiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L))
                .build());
        log.debug("refresh.issued user={}", LogMasker.email(user.getEmail()));
        return token;
    }

    /**
     * Rotates a refresh token: consumes the presented one and returns the
     * user it belonged to so the caller can mint a new access + refresh
     * pair. Throws {@link InvalidAuthTokenException} if the presented
     * token is unknown, expired, already consumed, or revoked.
     */
    @Transactional
    public User rotate(String presented) {
        var stored = tokenRepository.findByToken(CodeHasher.sha256(presented))
                .orElseThrow(InvalidAuthTokenException::new);
        if (!stored.isUsable(LocalDateTime.now())) {
            throw new InvalidAuthTokenException();
        }
        stored.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(stored);
        var user = stored.getUser();
        log.info("refresh.rotated user={}", LogMasker.email(user.getEmail()));
        return user;
    }

    /**
     * Best-effort revocation: silently ignores unknown / already-invalid
     * tokens so /logout is idempotent.
     */
    @Transactional
    public void revoke(String presented) {
        tokenRepository.findByToken(CodeHasher.sha256(presented)).ifPresent(stored -> {
            if (stored.getRevokedAt() == null && stored.getConsumedAt() == null) {
                stored.setRevokedAt(LocalDateTime.now());
                tokenRepository.save(stored);
                log.info("refresh.revoked user={}", LogMasker.email(stored.getUser().getEmail()));
            }
        });
    }

    /**
     * Revokes every active session for the user — the canonical recovery action
     * ("someone knows my password, I changed it") must evict the attacker too.
     */
    @Transactional
    public void revokeAllForUser(User user) {
        var revoked = tokenRepository.revokeAllActiveForUser(user);
        log.info("refresh.revoked_all user={} count={}", LogMasker.email(user.getEmail()), revoked);
    }

    private String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
