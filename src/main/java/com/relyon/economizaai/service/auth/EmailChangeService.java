package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.dto.request.ConfirmEmailChangeRequest;
import com.relyon.economizaai.dto.request.RequestEmailChangeRequest;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.exception.EmailChangeConflictException;
import com.relyon.economizaai.exception.InvalidCurrentPasswordException;
import com.relyon.economizaai.exception.InvalidEmailChangeCodeException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.User;
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

/**
 * Change of the account e-mail, with proof of ownership of the new address.
 *
 * <p>The request needs the current password and only records the PENDING address plus a
 * hashed 6-digit code sent to it — the account e-mail stays as it is. Confirming the code
 * switches the e-mail (already verified), signs the other sessions out and tells the old
 * address. Same hardening as the other codes: hash only, constant-time compare, a small
 * attempt budget, short expiry.
 *
 * <p>Every method reloads the user by id: the request principal is a detached snapshot.
 * Mails go through {@link AuthEmailSender}, which hands them to the async pool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailChangeService {

    private static final int CODE_TTL_MINUTES = 30;
    private static final int CODE_BOUND = 1_000_000;
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthEmailSender emailSender;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public void requestChange(User principal, RequestEmailChangeRequest request) {
        var user = reload(principal);
        if (user.getPassword() == null) {
            throw EmailChangeConflictException.passwordRequired();
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            log.warn("email_change.refused reason=bad_password user={}", LogMasker.email(user.getEmail()));
            throw new InvalidCurrentPasswordException();
        }
        var newEmail = EmailNormalizer.normalize(request.newEmail());
        if (newEmail.equals(user.getEmail())) {
            throw EmailChangeConflictException.sameEmail();
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw EmailChangeConflictException.emailInUse();
        }
        var code = generateCode();
        user.setPendingEmail(newEmail);
        user.setEmailChangeCodeHash(CodeHasher.sha256(code));
        user.setEmailChangeExpiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        user.setEmailChangeAttempts(0);
        userRepository.save(user);
        emailSender.sendEmailChangeCode(newEmail, LocalizedMessageService.toLocale(user.getLocale()),
                code, CODE_TTL_MINUTES);
        log.info("email_change.requested user={} new={} ttl_minutes={}",
                LogMasker.email(user.getEmail()), LogMasker.email(newEmail), CODE_TTL_MINUTES);
    }

    /**
     * noRollbackFor: a wrong guess must COMMIT its attempt count despite the thrown
     * exception, or the budget never fills.
     */
    @Transactional(noRollbackFor = InvalidEmailChangeCodeException.class)
    public UserResponse confirmChange(User principal, ConfirmEmailChangeRequest request) {
        var user = reload(principal);
        requireMatchingCode(user, request.code());
        var newEmail = user.getPendingEmail();
        if (userRepository.existsByEmail(newEmail)) {
            // Someone registered the address while the code was pending.
            clearPendingChange(user);
            userRepository.save(user);
            throw EmailChangeConflictException.emailInUse();
        }
        var oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        clearPendingChange(user);
        var saved = userRepository.save(user);
        refreshTokenService.revokeAllForUserExcept(saved, request.refreshToken());
        emailSender.sendEmailChangedNotice(oldEmail, LocalizedMessageService.toLocale(saved.getLocale()),
                LogMasker.email(newEmail));
        log.info("email_change.confirmed old={} new={}", LogMasker.email(oldEmail), LogMasker.email(newEmail));
        return UserResponse.from(saved);
    }

    @Transactional
    public void cancelChange(User principal) {
        var user = reload(principal);
        if (user.getPendingEmail() == null) return;
        clearPendingChange(user);
        userRepository.save(user);
        log.info("email_change.cancelled user={}", LogMasker.email(user.getEmail()));
    }

    private User reload(User principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new UserNotFoundException(principal.getId().toString()));
    }

    /** Counts every wrong guess; after the budget the code is dead even if guessed right. */
    private void requireMatchingCode(User user, String typedCode) {
        var noUsableCode = user.getPendingEmail() == null
                || user.getEmailChangeCodeHash() == null
                || user.getEmailChangeExpiresAt() == null
                || user.getEmailChangeExpiresAt().isBefore(LocalDateTime.now())
                || user.getEmailChangeAttempts() >= MAX_VERIFY_ATTEMPTS;
        if (noUsableCode) {
            throw new InvalidEmailChangeCodeException();
        }
        if (!CodeHasher.matches(typedCode, user.getEmailChangeCodeHash())) {
            user.setEmailChangeAttempts(user.getEmailChangeAttempts() + 1);
            userRepository.save(user);
            log.warn("email_change.wrong_code user={} attempts={}/{}",
                    LogMasker.email(user.getEmail()), user.getEmailChangeAttempts(), MAX_VERIFY_ATTEMPTS);
            throw new InvalidEmailChangeCodeException();
        }
    }

    private void clearPendingChange(User user) {
        user.setPendingEmail(null);
        user.setEmailChangeCodeHash(null);
        user.setEmailChangeExpiresAt(null);
        user.setEmailChangeAttempts(0);
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }
}
