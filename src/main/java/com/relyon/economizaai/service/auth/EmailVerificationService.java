package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.EmailVerificationToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.EmailVerificationTokenRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Email verification via a 6-digit code typed in the app — same UX and same
 * hardening as {@link PasswordResetService}: one active code per user, hash
 * stored (never the plaintext), constant-time comparison, and a small failed-
 * attempt budget before the code locks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int CODE_TTL_HOURS = 24;
    private static final int CODE_BOUND = 1_000_000;
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final AuthEmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    /** Called from UserService.register right after persisting a new user. */
    @Transactional
    public void sendVerificationFor(User user) {
        if (user.isEmailVerified()) return;
        // Kill any prior still-open code so only the newest one works.
        tokenRepository.consumeAllActiveForUser(user, LocalDateTime.now());
        var code = generateCode();
        tokenRepository.save(EmailVerificationToken.builder()
                .user(user)
                .token(CodeHasher.sha256(code))
                .expiresAt(LocalDateTime.now().plusHours(CODE_TTL_HOURS))
                .build());
        emailSender.sendEmailVerification(user.getEmail(),
                LocalizedMessageService.toLocale(user.getLocale()), code, CODE_TTL_HOURS);
        log.info("email_verification.sent user={} ttl_hours={}",
                LogMasker.email(user.getEmail()), CODE_TTL_HOURS);
    }

    @Transactional
    public void resend(User user) {
        if (user.isEmailVerified()) {
            // No throw — silently no-op so the FE can call this safely.
            return;
        }
        sendVerificationFor(user);
    }

    /**
     * Verifies the typed code against the user's active one, counting every
     * wrong guess. After {@link #MAX_VERIFY_ATTEMPTS} failures the code is dead
     * even if subsequently guessed right — the user must request a resend.
     */
    @Transactional
    public void verify(String email, String code) {
        var user = userRepository.findByEmail(email).orElseThrow(InvalidAuthTokenException::new);
        if (user.isEmailVerified()) return;   // idempotent — a second tap isn't an error
        var token = findValidCode(user, code);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        token.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(token);
        log.info("email_verification.completed user={}", LogMasker.email(user.getEmail()));
    }

    private EmailVerificationToken findValidCode(User user, String code) {
        var token = tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user)
                .orElseThrow(InvalidAuthTokenException::new);
        if (token.getExpiresAt().isBefore(LocalDateTime.now()) || token.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            throw new InvalidAuthTokenException();
        }
        if (!CodeHasher.matches(code, token.getToken())) {
            token.setAttempts(token.getAttempts() + 1);
            tokenRepository.save(token);
            log.warn("email_verification.wrong_code user={} attempts={}/{}",
                    LogMasker.email(user.getEmail()), token.getAttempts(), MAX_VERIFY_ATTEMPTS);
            throw new InvalidAuthTokenException();
        }
        return token;
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }
}
