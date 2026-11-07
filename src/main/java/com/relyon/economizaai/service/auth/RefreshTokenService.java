package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.RefreshTokenRejectedException;
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
import java.util.UUID;

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

    // Reapresentar um token JÁ consumido dentro desta janela é tratado como CORRIDA legítima (duas
    // abas / app+web / retry de rede renovando quase ao mesmo tempo), não reuso malicioso: rotaciona
    // de novo sem revogar nada. Reuso FORA da janela segue sinalizando roubo → revoga a família.
    @Value("${jwt.refresh-reuse-grace-ms:60000}")
    private long reuseGraceMs;

    /** Login/cadastro: abre uma família NOVA (um dispositivo = uma cadeia de rotações). */
    @Transactional
    public String issue(User user) {
        return issueInFamily(user, UUID.randomUUID());
    }

    /** Emite o próximo token de uma família existente (sucessor de uma rotação). */
    @Transactional
    public String issueInFamily(User user, UUID familyId) {
        var token = generateToken();
        tokenRepository.save(RefreshToken.builder()
                .user(user)
                .familyId(familyId)
                .token(CodeHasher.sha256(token))
                .expiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L))
                .build());
        log.debug("refresh.issued user={} family={}", LogMasker.email(user.getEmail()), familyId);
        return token;
    }

    /** Resultado de uma rotação: o dono + a família a que o sucessor pertence. */
    public record RotationResult(User user, UUID familyId) {}

    /**
     * Rotates a refresh token: consumes the presented one and returns the user + the family the
     * successor belongs to, so the caller can mint the next access + refresh pair IN THE SAME family.
     * Throws {@link RefreshTokenRejectedException} (→ 401, deslogar) when the token is unknown,
     * expired, revoked, or reused outside the grace window.
     *
     * <p>Reapresentar um token JÁ CONSUMIDO é o sinal clássico de replay. Mas dentro da
     * {@code reuseGraceMs} é quase sempre uma corrida legítima (duas abas / app+web / retry de rede
     * renovando quase junto): rotaciona de novo sem punir. Só o reuso TARDIO revoga — e agora apenas
     * a FAMÍLIA afetada (um dispositivo), não todas as sessões do usuário (o app não cai junto com o
     * web). {@code noRollbackFor} mantém a revogação comitada apesar do 401.
     */
    @Transactional(noRollbackFor = RefreshTokenRejectedException.class)
    public RotationResult rotate(String presented) {
        var stored = tokenRepository.findByToken(CodeHasher.sha256(presented))
                .orElseThrow(RefreshTokenRejectedException::new);
        var now = LocalDateTime.now();
        var familyId = familyOf(stored);
        if (stored.getConsumedAt() != null) {
            var withinGrace = stored.getRevokedAt() == null
                    && stored.getConsumedAt().isAfter(now.minusNanos(reuseGraceMs * 1_000_000L));
            if (withinGrace) {
                log.info("refresh.rotated_within_grace user={} family={}",
                        LogMasker.email(stored.getUser().getEmail()), familyId);
                return new RotationResult(stored.getUser(), familyId);
            }
            revokeFamilyOnReuse(stored, familyId);
            throw new RefreshTokenRejectedException();
        }
        if (!stored.isUsable(now)) {
            throw new RefreshTokenRejectedException();
        }
        stored.setConsumedAt(now);
        tokenRepository.save(stored);
        log.info("refresh.rotated user={} family={}", LogMasker.email(stored.getUser().getEmail()), familyId);
        return new RotationResult(stored.getUser(), familyId);
    }

    /** Tokens pré-V132 não têm família gravada — cada um é a sua própria família (o próprio id). */
    private static UUID familyOf(RefreshToken token) {
        return token.getFamilyId() != null ? token.getFamilyId() : token.getId();
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

    private void revokeFamilyOnReuse(RefreshToken reused, UUID familyId) {
        var owner = reused.getUser();
        var revoked = tokenRepository.revokeActiveForFamily(familyId);
        log.warn("refresh_token.reuse_detected user={} family={} revoked={}",
                LogMasker.email(owner.getEmail()), familyId, revoked);
    }

    private String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
