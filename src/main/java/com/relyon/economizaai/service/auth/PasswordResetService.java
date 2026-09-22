package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.dto.request.ForgotPasswordRequest;
import com.relyon.economizaai.dto.request.ResetPasswordRequest;
import com.relyon.economizaai.dto.request.VerifyResetCodeRequest;
import com.relyon.economizaai.exception.InvalidAuthTokenException;
import com.relyon.economizaai.model.PasswordResetToken;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.repository.PasswordResetTokenRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int CODE_TTL_MINUTES = 60;
    // 6-digit code: short enough to type on a phone, with a TTL + single-active-code
    // + single-use to bound brute-force (10^6 space, one live code, 60-min window).
    private static final int CODE_BOUND = 1_000_000;
    // Wrong guesses allowed against one code before it locks — with 10^6 codes this
    // caps the success probability per issued code at 0.0005%.
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthEmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    /**
     * Always returns success even when the email isn't registered — prevents
     * an attacker from probing valid emails. Real users get the email; bad
     * actors get a no-op identical to the success path.
     */
    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            log.info("password_reset.requested unknown_email={}", LogMasker.email(request.email()));
            return;
        }
        var user = userOpt.get();
        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            // Social-only account has no password to reset — silent no-op, same as an
            // unknown email, so we don't leak which accounts are social.
            log.info("password_reset.requested social_account_noop user={}", LogMasker.email(user.getEmail()));
            return;
        }
        // Kill any prior still-open code so only the newest one works.
        tokenRepository.consumeAllActiveForUser(user, LocalDateTime.now());
        var code = generateCode();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .token(CodeHasher.sha256(code))   // only the hash is stored; the email carries the code
                .expiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES))
                .build());
        emailSender.sendPasswordResetCode(user.getEmail(),
                LocalizedMessageService.toLocale(user.getLocale()), code, CODE_TTL_MINUTES);
        log.info("password_reset.requested user={} ttl_minutes={}",
                LogMasker.email(user.getEmail()), CODE_TTL_MINUTES);
    }

    /**
     * Validate a code WITHOUT consuming it, so the app can gate the new-password
     * screen on a correct code before the user types anything. Throws on a bad /
     * expired / consumed code; returns quietly on success. Not readOnly: failed
     * guesses increment the attempt counter.
     */
    @Transactional
    public void verifyCode(VerifyResetCodeRequest request) {
        var user = userRepository.findByEmail(request.email()).orElseThrow(InvalidAuthTokenException::new);
        findValidCode(user, request.code());   // throws if invalid
        log.info("password_reset.code_verified user={}", LogMasker.email(user.getEmail()));
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        var user = userRepository.findByEmail(request.email()).orElseThrow(InvalidAuthTokenException::new);
        var token = findValidCode(user, request.code());
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        token.setConsumedAt(LocalDateTime.now());
        tokenRepository.save(token);
        log.info("password_reset.completed user={}", LogMasker.email(user.getEmail()));
    }

    /**
     * Loads the user's ACTIVE code and compares against the typed one, counting
     * every wrong guess. After {@link #MAX_VERIFY_ATTEMPTS} failures the code is
     * dead even if subsequently guessed right — the user must request a new one.
     * Comparison is constant-time so response timing leaks nothing.
     */
    private PasswordResetToken findValidCode(User user, String code) {
        var token = tokenRepository.findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(user)
                .orElseThrow(InvalidAuthTokenException::new);
        if (token.getExpiresAt().isBefore(LocalDateTime.now()) || token.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            throw new InvalidAuthTokenException();
        }
        if (!CodeHasher.matches(code, token.getToken())) {
            token.setAttempts(token.getAttempts() + 1);
            tokenRepository.save(token);
            log.warn("password_reset.wrong_code user={} attempts={}/{}",
                    LogMasker.email(user.getEmail()), token.getAttempts(), MAX_VERIFY_ATTEMPTS);
            throw new InvalidAuthTokenException();
        }
        return token;
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }
}
