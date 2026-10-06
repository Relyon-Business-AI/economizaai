package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.SubmitMerchantClaimRequest;
import com.relyon.economizaai.dto.response.MerchantClaimResponse;
import com.relyon.economizaai.exception.AdminRoleChangeException;
import com.relyon.economizaai.exception.MerchantChainAlreadyGrantedException;
import com.relyon.economizaai.exception.MerchantClaimAlreadyOpenException;
import com.relyon.economizaai.exception.MerchantClaimAlreadyResolvedException;
import com.relyon.economizaai.exception.MerchantClaimAttemptsExceededException;
import com.relyon.economizaai.exception.MerchantClaimCodeExpiredException;
import com.relyon.economizaai.exception.MerchantClaimCodeInvalidException;
import com.relyon.economizaai.exception.MerchantClaimNotFoundException;
import com.relyon.economizaai.exception.MerchantClaimNotVerifiableException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.MerchantClaim;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantClaimStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.MerchantClaimRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.auth.AuthEmailSender;
import com.relyon.economizaai.service.auth.CodeHasher;
import com.relyon.economizaai.service.geo.CnpjActivityClient;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-serve "sou este mercado" (docs/MERCHANT_ACCOUNTS.md, Fase 1.5).
 *
 * <p>Verification: the claim's CNPJ is looked up at the Receita (BrasilAPI);
 * when a company e-mail is registered there, a 6-digit code goes to THAT
 * address — only someone with access to the company mailbox can approve.
 * Without a usable e-mail the claim waits in the admin review queue.
 *
 * <p>{@link #submit} is deliberately NOT transactional: the BrasilAPI call is
 * outbound HTTP and must never pin a connection (CLAUDE.md); each repository
 * save is its own short transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantClaimService {

    private static final List<MerchantClaimStatus> OPEN_STATUSES =
            List.of(MerchantClaimStatus.AWAITING_CODE, MerchantClaimStatus.PENDING_REVIEW);
    private static final Locale COMPANY_LOCALE = Locale.forLanguageTag("pt");

    private final MerchantClaimRepository claimRepository;
    private final MerchantAccessRepository merchantAccessRepository;
    private final UserRepository userRepository;
    private final CnpjActivityClient cnpjActivityClient;
    private final AuthEmailSender authEmailSender;
    private final ContactService contactService;
    private final MerchantSubscriptionService merchantSubscriptionService;
    private final CollaborativeProperties properties;
    private final SecureRandom random = new SecureRandom();

    public MerchantClaimResponse submit(User user, SubmitMerchantClaimRequest request) {
        var cnpj = request.cnpj();
        var cnpjRoot = PriceIndexService.cnpjRoot(cnpj);
        rejectDuplicates(user, cnpjRoot);

        var lookup = cnpjActivityClient.lookup(cnpj);
        var companyName = request.companyName() != null ? request.companyName() : lookup.razaoSocial();
        var claim = lookup.hasEmail()
                ? submitWithEmailCode(user, cnpj, cnpjRoot, companyName, request.contactPhone(), lookup.email())
                : submitForAdminReview(user, cnpj, cnpjRoot, companyName, request.contactPhone());
        return MerchantClaimResponse.from(claim);
    }

    private void rejectDuplicates(User user, String cnpjRoot) {
        if (merchantAccessRepository.existsByUserIdAndCnpjRoot(user.getId(), cnpjRoot)) {
            throw new MerchantChainAlreadyGrantedException(cnpjRoot);
        }
        if (claimRepository.existsByUserIdAndCnpjRootAndStatusIn(user.getId(), cnpjRoot, OPEN_STATUSES)) {
            throw new MerchantClaimAlreadyOpenException(cnpjRoot);
        }
    }

    private MerchantClaim submitWithEmailCode(User user, String cnpj, String cnpjRoot,
                                              String companyName, String contactPhone, String companyEmail) {
        var code = String.format("%06d", random.nextInt(1_000_000));
        var ttlHours = properties.getMerchant().getClaimCodeTtlHours();
        var claim = claimRepository.save(MerchantClaim.builder()
                .user(user).cnpj(cnpj).cnpjRoot(cnpjRoot)
                .companyName(companyName).contactPhone(contactPhone)
                .status(MerchantClaimStatus.AWAITING_CODE)
                .verificationCodeHash(CodeHasher.sha256(code))
                .verificationEmailMasked(LogMasker.email(companyEmail))
                .codeExpiresAt(BrazilClock.nowDateTime().plusHours(ttlHours))
                .build());
        // Async internally; company e-mail is always pt (Brazilian establishments).
        authEmailSender.sendMerchantClaimCode(companyEmail, COMPANY_LOCALE, code, ttlHours);
        log.info("merchant.claim.code_sent claim={} cnpjRoot={} email={}",
                claim.getId(), cnpjRoot, LogMasker.email(companyEmail));
        return claim;
    }

    private MerchantClaim submitForAdminReview(User user, String cnpj, String cnpjRoot,
                                               String companyName, String contactPhone) {
        var claim = claimRepository.save(MerchantClaim.builder()
                .user(user).cnpj(cnpj).cnpjRoot(cnpjRoot)
                .companyName(companyName).contactPhone(contactPhone)
                .status(MerchantClaimStatus.PENDING_REVIEW)
                .build());
        log.info("merchant.claim.pending_review claim={} cnpjRoot={}", claim.getId(), cnpjRoot);
        notifyAdminOfPendingClaim(claim);
        return claim;
    }

    /** Best-effort — a failed admin alert never fails the claim (it's still in the queue). */
    private void notifyAdminOfPendingClaim(MerchantClaim claim) {
        try {
            contactService.notifyAdmin(
                    "nova reivindicação de mercado aguardando revisão: " + claim.getCompanyName(),
                    """
                    Um usuário reivindicou um mercado, mas o CNPJ não tem e-mail utilizável na \
                    Receita — a reivindicação espera seu veredito em /admin/merchant-claims.

                    - CNPJ: %s (rede %s)
                    - Empresa: %s
                    - Usuário: %s
                    """.formatted(claim.getCnpj(), claim.getCnpjRoot(),
                            claim.getCompanyName(), claim.getUser().getEmail()));
        } catch (RuntimeException ex) {
            log.warn("merchant.claim.admin_alert_failed claim={} {}", claim.getId(), ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<MerchantClaimResponse> myClaims(User user) {
        return claimRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(MerchantClaimResponse::from)
                .toList();
    }

    /**
     * Code check. Failures must COMMIT their side effects (attempt counter,
     * terminal status) while still surfacing a 4xx — hence the noRollbackFor list.
     * Expired/exhausted claims are closed (REJECTED) so the partial-unique open
     * index frees up and the user can simply submit again.
     */
    @Transactional(noRollbackFor = {
            MerchantClaimCodeInvalidException.class,
            MerchantClaimCodeExpiredException.class,
            MerchantClaimAttemptsExceededException.class})
    public MerchantClaimResponse verify(User user, UUID claimId, String typedCode) {
        var claim = claimRepository.findByIdAndUserId(claimId, user.getId())
                .orElseThrow(MerchantClaimNotFoundException::new);
        if (claim.getStatus() != MerchantClaimStatus.AWAITING_CODE) {
            throw new MerchantClaimNotVerifiableException();
        }
        if (claim.getCodeExpiresAt() == null || claim.getCodeExpiresAt().isBefore(BrazilClock.nowDateTime())) {
            closeClaim(claim, "code_expired");
            throw new MerchantClaimCodeExpiredException();
        }
        if (claim.getCodeAttempts() >= properties.getMerchant().getClaimCodeMaxAttempts()) {
            closeClaim(claim, "code_attempts_exceeded");
            throw new MerchantClaimAttemptsExceededException();
        }
        if (!CodeHasher.matches(typedCode, claim.getVerificationCodeHash())) {
            claim.setCodeAttempts(claim.getCodeAttempts() + 1);
            claimRepository.save(claim);
            log.info("merchant.claim.code_mismatch claim={} attempts={}", claim.getId(), claim.getCodeAttempts());
            throw new MerchantClaimCodeInvalidException();
        }
        approve(claim, null);
        return MerchantClaimResponse.from(claim);
    }

    @Transactional
    public MerchantClaimResponse approveByAdmin(UUID claimId, UUID adminUserId) {
        var claim = requireOpenClaim(claimId);
        approve(claim, adminUserId);
        return MerchantClaimResponse.from(claim);
    }

    @Transactional
    public MerchantClaimResponse rejectByAdmin(UUID claimId, UUID adminUserId, String reason) {
        var claim = requireOpenClaim(claimId);
        claim.setStatus(MerchantClaimStatus.REJECTED);
        claim.setRejectionReason(reason);
        claim.setReviewedByUserId(adminUserId);
        claim.setResolvedAt(BrazilClock.nowDateTime());
        claimRepository.save(claim);
        log.info("merchant.claim.rejected claim={} admin={}", claim.getId(), adminUserId);
        return MerchantClaimResponse.from(claim);
    }

    private MerchantClaim requireOpenClaim(UUID claimId) {
        var claim = claimRepository.findById(claimId).orElseThrow(MerchantClaimNotFoundException::new);
        if (!claim.isOpen()) {
            throw new MerchantClaimAlreadyResolvedException();
        }
        return claim;
    }

    /**
     * Shared approval path (code auto-approve and admin verdict): promote the
     * user to MERCHANT, grant the chain, open the marketing subscription with
     * the launch promo, close the claim.
     */
    private void approve(MerchantClaim claim, UUID reviewerUserId) {
        var user = claim.getUser();
        if (user.getRole() == Role.ADMIN) {
            throw new AdminRoleChangeException();
        }
        if (user.getRole() != Role.MERCHANT) {
            user.setRole(Role.MERCHANT);
            userRepository.save(user);
        }
        if (!merchantAccessRepository.existsByUserIdAndCnpjRoot(user.getId(), claim.getCnpjRoot())) {
            merchantAccessRepository.save(MerchantAccess.builder()
                    .user(user).cnpjRoot(claim.getCnpjRoot()).build());
        }
        merchantSubscriptionService.ensureForChain(claim.getCnpjRoot());
        claim.setStatus(MerchantClaimStatus.APPROVED);
        claim.setReviewedByUserId(reviewerUserId);
        claim.setResolvedAt(BrazilClock.nowDateTime());
        claimRepository.save(claim);
        log.info("merchant.claim.approved claim={} cnpjRoot={} user={} via={}",
                claim.getId(), claim.getCnpjRoot(), user.getId(),
                reviewerUserId == null ? "email_code" : "admin");
    }

    @Transactional(readOnly = true)
    public List<MerchantClaim> pendingReview() {
        return claimRepository.findAllByStatusOrderByCreatedAtAsc(MerchantClaimStatus.PENDING_REVIEW);
    }

    private void closeClaim(MerchantClaim claim, String reason) {
        claim.setStatus(MerchantClaimStatus.REJECTED);
        claim.setRejectionReason(reason);
        claim.setResolvedAt(BrazilClock.nowDateTime());
        claimRepository.save(claim);
        log.info("merchant.claim.closed claim={} reason={}", claim.getId(), reason);
    }
}
