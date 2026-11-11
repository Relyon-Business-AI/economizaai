package com.relyon.economizaai.service.referral;

import com.relyon.economizaai.dto.response.ReferralStatusResponse;
import com.relyon.economizaai.exception.ReferralCodeRejectedException;
import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.Referral;
import com.relyon.economizaai.model.ReferralCode;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PremiumGrantRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReferralCodeRepository;
import com.relyon.economizaai.repository.ReferralRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.subscription.EntitlementClock;
import com.relyon.economizaai.service.subscription.PremiumGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Referral program ("indique um amigo"). Rules:
 * <ul>
 *   <li>Every user has a stable personal code. A NEW account may be attached to an inviter — at
 *       signup, or later through redeem while it still has no confirmed receipt. One referrer
 *       per account, ever.</li>
 *   <li>A referral QUALIFIES when the invited person confirms a receipt that contributed to the
 *       collaborative index (at least one price observation) while living in a different
 *       household than the inviter.</li>
 *   <li>The inviter's FIRST qualified referral earns one month of Premium — once per inviter.
 *       After that, a month is earned for each invited person who SUBSCRIBES to paid Premium.
 *       An invited person yields at most one month; the invited person earns nothing.</li>
 *   <li>Months accumulate: each one queues after the inviter's current entitlement
 *       ({@link PremiumGrantService}).</li>
 * </ul>
 * The reward paths run in their OWN transaction and never propagate a failure: a referral
 * problem must not break a receipt confirmation or a billing webhook.
 */
@Slf4j
@Service
public class ReferralService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 7;
    private static final int CODE_GENERATION_ATTEMPTS = 10;
    private static final String EXTRA_KIND = "REFERRAL_REWARD";
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;
    private final PremiumGrantRepository premiumGrantRepository;
    private final PremiumGrantService premiumGrantService;
    private final NotificationService notificationService;
    private final LocalizedMessageService localizedMessageService;
    private final TransactionTemplate ownTransaction;
    private final String shareBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRepository referralRepository,
                           UserRepository userRepository,
                           ReceiptRepository receiptRepository,
                           PriceObservationAuditRepository priceObservationAuditRepository,
                           PremiumGrantRepository premiumGrantRepository,
                           PremiumGrantService premiumGrantService,
                           NotificationService notificationService,
                           LocalizedMessageService localizedMessageService,
                           PlatformTransactionManager transactionManager,
                           @Value("${economizaai.referral.share-base-url:https://economizaai.app/?ref=}") String shareBaseUrl) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.userRepository = userRepository;
        this.receiptRepository = receiptRepository;
        this.priceObservationAuditRepository = priceObservationAuditRepository;
        this.premiumGrantRepository = premiumGrantRepository;
        this.premiumGrantService = premiumGrantService;
        this.notificationService = notificationService;
        this.localizedMessageService = localizedMessageService;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shareBaseUrl = shareBaseUrl;
    }

    // --- Code + status ---

    /** Not transactional on purpose: a lost race creating the code must not poison the read. */
    public ReferralStatusResponse statusFor(User user) {
        var code = findOrCreateCode(user);
        var referred = referralRepository.existsByInvitedId(user.getId());
        return new ReferralStatusResponse(
                code,
                shareBaseUrl + code,
                referralRepository.countByInviterId(user.getId()),
                referralRepository.countByInviterIdAndQualifiedAtIsNotNull(user.getId()),
                premiumGrantRepository.countByUserId(user.getId()),
                premiumGrantRepository.existsByUserIdAndKind(user.getId(), PremiumGrantKind.REFERRAL_FIRST),
                referred,
                !referred && !hasConfirmedReceipt(user));
    }

    /** The user's stable code, generated (and saved) the first time it is asked for. */
    private String findOrCreateCode(User user) {
        var existing = referralCodeRepository.findByUserId(user.getId());
        if (existing.isPresent()) {
            return existing.get().getCode();
        }
        try {
            var created = referralCodeRepository.saveAndFlush(ReferralCode.builder()
                    .userId(user.getId())
                    .code(generateUnusedCode())
                    .build());
            log.info("referral.code_created user={}", LogMasker.email(user.getEmail()));
            return created.getCode();
        } catch (DataIntegrityViolationException concurrentCreation) {
            // Two first reads at once: the other one won; its code is the user's code.
            return referralCodeRepository.findByUserId(user.getId())
                    .map(ReferralCode::getCode)
                    .orElseThrow(() -> concurrentCreation);
        }
    }

    private String generateUnusedCode() {
        for (var attempt = 0; attempt < CODE_GENERATION_ATTEMPTS; attempt++) {
            var candidate = randomCode();
            if (!referralCodeRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not generate an unused referral code");
    }

    private String randomCode() {
        var builder = new StringBuilder(CODE_LENGTH);
        for (var position = 0; position < CODE_LENGTH; position++) {
            builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    // --- Attribution ---

    /**
     * Signup hook: attaches the brand-new account to the owner of {@code rawCode}. Silent by
     * contract — a missing, unknown or own code is ignored and never fails the registration.
     */
    @Transactional
    public void attachOnSignup(User newUser, String rawCode) {
        var code = normalize(rawCode);
        if (code == null) {
            return;
        }
        var inviterId = referralCodeRepository.findByCode(code).map(ReferralCode::getUserId).orElse(null);
        if (inviterId == null || inviterId.equals(newUser.getId())
                || referralRepository.existsByInvitedId(newUser.getId())) {
            log.info("referral.signup_code_ignored user={} reason={}", LogMasker.email(newUser.getEmail()),
                    inviterId == null ? "unknown_code" : "not_applicable");
            return;
        }
        saveReferral(inviterId, newUser);
        log.info("referral.attached user={} via=signup", LogMasker.email(newUser.getEmail()));
    }

    /**
     * Enter an inviter's code after signup. Allowed only while the account was never referred and
     * has no confirmed receipt yet (so the qualifying receipt is always a new one).
     */
    @Transactional
    public void redeem(User user, String rawCode) {
        var code = normalize(rawCode);
        var inviterId = code == null
                ? null
                : referralCodeRepository.findByCode(code).map(ReferralCode::getUserId).orElse(null);
        if (inviterId == null) {
            throw ReferralCodeRejectedException.invalidCode();
        }
        if (inviterId.equals(user.getId())) {
            throw ReferralCodeRejectedException.ownCode();
        }
        if (referralRepository.existsByInvitedId(user.getId())) {
            throw ReferralCodeRejectedException.alreadyReferred();
        }
        if (hasConfirmedReceipt(user)) {
            throw ReferralCodeRejectedException.tooLate();
        }
        if (referralRepository.existsByInviterIdAndInvitedId(user.getId(), inviterId)) {
            // The code's owner was invited by this very user — two accounts can't refer each other.
            throw ReferralCodeRejectedException.invalidCode();
        }
        saveReferral(inviterId, user);
        log.info("referral.attached user={} via=redeem", LogMasker.email(user.getEmail()));
    }

    private void saveReferral(UUID inviterId, User invited) {
        referralRepository.save(Referral.builder()
                .inviter(userRepository.getReferenceById(inviterId))
                .invited(invited)
                .build());
    }

    private boolean hasConfirmedReceipt(User user) {
        return receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED) > 0;
    }

    private static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        return rawCode.trim().toUpperCase(Locale.ROOT);
    }

    // --- Rewards ---

    /**
     * Confirm hook: once the confirmation COMMITS, checks whether this receipt qualifies the
     * user's referral (and rewards the inviter). Deferred so the price observations are visible
     * and a referral failure can never roll the confirmation back.
     */
    public void qualifyAfterCommit(UUID invitedUserId, UUID receiptId) {
        afterCommitOrNow(() -> rewardSafely("qualify", () -> qualify(invitedUserId, receiptId)));
    }

    /**
     * Billing hook: the invited user made a paid Premium purchase — reward their inviter (first
     * time only). Deferred past the caller's transaction, when there is one, for the same reasons.
     */
    public void rewardInviterForSubscription(User invited) {
        var invitedUserId = invited.getId();
        afterCommitOrNow(() -> rewardSafely("subscription", () -> rewardForSubscription(invitedUserId)));
    }

    private void afterCommitOrNow(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }

    /**
     * Runs a reward decision in its own transaction, then notifies the inviter OUTSIDE it (the
     * dispatch is outbound HTTP). Never propagates.
     */
    private void rewardSafely(String trigger, Supplier<Optional<NotificationPayload>> rewardDecision) {
        try {
            var notification = ownTransaction.execute(status -> rewardDecision.get());
            if (notification != null && notification.isPresent()) {
                notificationService.notify(notification.get());
            }
        } catch (RuntimeException failure) {
            log.warn("referral.reward_failed trigger={} reason={}", trigger, failure.getMessage());
        }
    }

    private Optional<NotificationPayload> qualify(UUID invitedUserId, UUID receiptId) {
        var referral = referralRepository.findByInvitedIdWithInviter(invitedUserId).orElse(null);
        if (referral == null || referral.getQualifiedAt() != null) {
            return Optional.empty();
        }
        if (!priceObservationAuditRepository.existsByReceiptId(receiptId)) {
            log.info("referral.not_qualified reason=receipt_did_not_contribute invited={}",
                    LogMasker.email(referral.getInvited().getEmail()));
            return Optional.empty();
        }
        if (shareHousehold(referral)) {
            log.info("referral.not_qualified reason=same_household invited={}",
                    LogMasker.email(referral.getInvited().getEmail()));
            return Optional.empty();
        }
        referral.setQualifiedAt(LocalDateTime.now());
        referral.setQualifyingReceiptId(receiptId);
        referralRepository.save(referral);
        var inviter = referral.getInviter();
        var firstRewardUsed = premiumGrantRepository.existsByUserIdAndKind(inviter.getId(), PremiumGrantKind.REFERRAL_FIRST);
        log.info("referral.qualified inviter={} firstRewardUsed={}", LogMasker.email(inviter.getEmail()), firstRewardUsed);
        if (firstRewardUsed) {
            return Optional.empty();
        }
        return premiumGrantService.grantMonth(inviter, PremiumGrantKind.REFERRAL_FIRST, invitedUserId)
                .map(grant -> rewardNotification(inviter, grant, "notification.referral_first_reward"));
    }

    private Optional<NotificationPayload> rewardForSubscription(UUID invitedUserId) {
        var referral = referralRepository.findByInvitedIdWithInviter(invitedUserId).orElse(null);
        if (referral == null || referral.getSubscribedAt() != null) {
            return Optional.empty();
        }
        referral.setSubscribedAt(LocalDateTime.now());
        referralRepository.save(referral);
        if (shareHousehold(referral)) {
            log.info("referral.subscription_not_rewarded reason=same_household invited={}",
                    LogMasker.email(referral.getInvited().getEmail()));
            return Optional.empty();
        }
        var inviter = referral.getInviter();
        log.info("referral.invited_subscribed inviter={}", LogMasker.email(inviter.getEmail()));
        return premiumGrantService.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invitedUserId)
                .map(grant -> rewardNotification(inviter, grant, "notification.referral_subscription_reward"));
    }

    private static boolean shareHousehold(Referral referral) {
        return referral.getInviter().getHousehold().getId()
                .equals(referral.getInvited().getHousehold().getId());
    }

    private NotificationPayload rewardNotification(User inviter, PremiumGrant grant, String messageKeyPrefix) {
        var locale = LocalizedMessageService.toLocale(inviter.getLocale());
        var firstDay = EntitlementClock.brazilDayOf(grant.getStartsAt());
        var lastDay = EntitlementClock.brazilDayOf(grant.getEndsAt().minusSeconds(1));
        var title = localizedMessageService.translate(messageKeyPrefix + ".title", locale);
        var body = localizedMessageService.translate(messageKeyPrefix + ".body", locale,
                firstDay.format(DISPLAY_DATE), lastDay.format(DISPLAY_DATE));
        return new NotificationPayload(inviter, NotificationType.SYSTEM, title, body,
                Map.of("kind", EXTRA_KIND,
                        "rewardKind", grant.getKind().name(),
                        "startsOn", firstDay.toString(),
                        "endsOn", lastDay.toString()));
    }
}
