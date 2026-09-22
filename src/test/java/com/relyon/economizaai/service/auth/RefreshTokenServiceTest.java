package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.RefreshToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long THIRTY_DAYS_MS = 2_592_000_000L;

    @Mock
    private RefreshTokenRepository tokenRepository;

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(tokenRepository);
        ReflectionTestUtils.setField(service, "refreshExpirationMs", THIRTY_DAYS_MS);
        user = User.builder().email("test@test.com").password("encoded").build();
    }

    @Test
    void issue_savesTokenForUserWithFutureExpiry() {
        var returnedToken = service.issue(user);

        var captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokenRepository).save(captor.capture());
        var saved = captor.getValue();

        assertNotNull(returnedToken);
        // Only the SHA-256 is at rest — a DB dump must not yield usable sessions.
        assertEquals(CodeHasher.sha256(returnedToken), saved.getToken());
        assertNotEquals(returnedToken, saved.getToken());
        assertEquals(user, saved.getUser());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()), "expiry must be in the future");
        assertNull(saved.getConsumedAt());
        assertNull(saved.getRevokedAt());
    }

    @Test
    void issue_generatesUniqueTokens() {
        var firstToken = service.issue(user);
        var secondToken = service.issue(user);

        assertNotEquals(firstToken, secondToken);
    }

    @Test
    void rotate_consumesUsableTokenAndReturnsUser() {
        var stored = usableToken("presented-token");
        when(tokenRepository.findByToken(CodeHasher.sha256("presented-token"))).thenReturn(Optional.of(stored));

        var rotatedUser = service.rotate("presented-token");

        assertEquals(user, rotatedUser);
        assertNotNull(stored.getConsumedAt(), "rotated token must be marked consumed");
        verify(tokenRepository).save(stored);
    }

    @Test
    void rotate_unknownToken_throws() {
        when(tokenRepository.findByToken(CodeHasher.sha256("nope"))).thenReturn(Optional.empty());

        assertThrows(InvalidAuthTokenException.class, () -> service.rotate("nope"));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void rotate_expiredToken_throws() {
        var stored = usableToken("expired-token");
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByToken(CodeHasher.sha256("expired-token"))).thenReturn(Optional.of(stored));

        assertThrows(InvalidAuthTokenException.class, () -> service.rotate("expired-token"));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void rotate_alreadyConsumedToken_throws_singleUse() {
        var stored = usableToken("reused-token");
        stored.setConsumedAt(LocalDateTime.now().minusMinutes(5));
        when(tokenRepository.findByToken(CodeHasher.sha256("reused-token"))).thenReturn(Optional.of(stored));

        assertThrows(InvalidAuthTokenException.class, () -> service.rotate("reused-token"));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void rotate_revokedToken_throws() {
        var stored = usableToken("revoked-token");
        stored.setRevokedAt(LocalDateTime.now().minusMinutes(5));
        when(tokenRepository.findByToken(CodeHasher.sha256("revoked-token"))).thenReturn(Optional.of(stored));

        assertThrows(InvalidAuthTokenException.class, () -> service.rotate("revoked-token"));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void revoke_marksUsableTokenRevoked() {
        var stored = usableToken("logout-token");
        when(tokenRepository.findByToken(CodeHasher.sha256("logout-token"))).thenReturn(Optional.of(stored));

        service.revoke("logout-token");

        assertNotNull(stored.getRevokedAt(), "revoked token must carry revoked_at");
        verify(tokenRepository).save(stored);
    }

    @Test
    void revoke_unknownToken_isSilentNoOp() {
        when(tokenRepository.findByToken(CodeHasher.sha256("unknown"))).thenReturn(Optional.empty());

        service.revoke("unknown");

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void revoke_alreadyRevokedToken_doesNotSaveAgain() {
        var stored = usableToken("twice-revoked");
        var originalRevokedAt = LocalDateTime.now().minusHours(1);
        stored.setRevokedAt(originalRevokedAt);
        when(tokenRepository.findByToken(CodeHasher.sha256("twice-revoked"))).thenReturn(Optional.of(stored));

        service.revoke("twice-revoked");

        assertEquals(originalRevokedAt, stored.getRevokedAt());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void revoke_consumedToken_isIgnored() {
        var stored = usableToken("consumed-token");
        stored.setConsumedAt(LocalDateTime.now().minusMinutes(10));
        when(tokenRepository.findByToken(CodeHasher.sha256("consumed-token"))).thenReturn(Optional.of(stored));

        service.revoke("consumed-token");

        assertNull(stored.getRevokedAt());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void revokeAllForUser_bulkRevokesActiveSessions() {
        when(tokenRepository.revokeAllActiveForUser(user)).thenReturn(3);

        service.revokeAllForUser(user);

        verify(tokenRepository).revokeAllActiveForUser(user);
    }

    private RefreshToken usableToken(String tokenValue) {
        return RefreshToken.builder()
                .user(user)
                .token(CodeHasher.sha256(tokenValue))
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();
    }
}
