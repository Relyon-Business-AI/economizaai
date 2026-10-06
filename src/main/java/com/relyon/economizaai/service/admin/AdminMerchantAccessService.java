package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.MerchantAccessResponse;
import com.relyon.economizaai.exception.MerchantAccessAlreadyExistsException;
import com.relyon.economizaai.exception.MerchantAccessNotFoundException;
import com.relyon.economizaai.exception.MerchantRoleRequiredException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.merchant.MerchantSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Admin-managed chain grants for MERCHANT users (docs/MERCHANT_ACCOUNTS.md).
 * A grant is a (user, cnpj_root) pair — the chain's first 8 CNPJ digits —
 * and is what the merchant panel scopes every read to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMerchantAccessService {

    private final UserRepository userRepository;
    private final MerchantAccessRepository merchantAccessRepository;
    private final MerchantSubscriptionService merchantSubscriptionService;

    @Transactional(readOnly = true)
    public List<MerchantAccessResponse> list(UUID userId) {
        requireUser(userId);
        return merchantAccessRepository.findAllByUserId(userId).stream()
                .map(MerchantAccessResponse::from)
                .toList();
    }

    /** Grants a chain to a MERCHANT user. Refused on any other role so grants never dangle. */
    @Transactional
    public MerchantAccessResponse grant(UUID userId, String cnpjRoot) {
        var user = requireUser(userId);
        if (user.getRole() != Role.MERCHANT) {
            throw new MerchantRoleRequiredException();
        }
        if (merchantAccessRepository.existsByUserIdAndCnpjRoot(userId, cnpjRoot)) {
            throw new MerchantAccessAlreadyExistsException(cnpjRoot);
        }
        var access = merchantAccessRepository.save(MerchantAccess.builder()
                .user(user)
                .cnpjRoot(cnpjRoot)
                .build());
        // Any granted chain gets a marketing subscription (launch promo) — same as the
        // claim path, so admin-created merchant accounts can publish promos right away.
        merchantSubscriptionService.ensureForChain(cnpjRoot);
        log.info("admin.merchant_access.granted userId={} cnpjRoot={}", userId, cnpjRoot);
        return MerchantAccessResponse.from(access);
    }

    @Transactional
    public void revoke(UUID userId, String cnpjRoot) {
        requireUser(userId);
        var access = merchantAccessRepository.findByUserIdAndCnpjRoot(userId, cnpjRoot)
                .orElseThrow(() -> new MerchantAccessNotFoundException(cnpjRoot));
        merchantAccessRepository.delete(access);
        log.info("admin.merchant_access.revoked userId={} cnpjRoot={}", userId, cnpjRoot);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId.toString()));
    }
}
