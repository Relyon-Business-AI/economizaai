package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.AdminUserDetailResponse.ReceiptCounts;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.exception.AdminUserDeletionException;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.repository.InsightsRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.UserService;
import com.relyon.economizai.service.privacy.LogMasker;
import com.relyon.economizai.service.subscription.SubscriptionService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only operations on users: paginated list with optional name/email
 * search, and a detail view that bundles household stats + receipt counts +
 * 30-day spend snapshot.
 *
 * <p>Single responsibility: read-only views for ops triage. No mutations
 * here — promotion/deactivation flows would belong in a separate service
 * if/when we add them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int SPEND_WINDOW_DAYS = 30;

    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final InsightsRepository insightsRepository;
    private final SubscriptionService subscriptionService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> list(String search, Pageable pageable) {
        var trimmed = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        return userRepository.findAll(searchSpec(trimmed), sortedPageable).map(AdminUserSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        log.info("admin.user.get userId={}", userId);
        return detail(user);
    }

    /**
     * Set a user's subscription tier directly (testing / promos / ops). PRO
     * activates a manual subscription; FREE cancels it. Both keep the
     * subscription record and the user's tier in sync via SubscriptionService.
     */
    @Transactional
    public AdminUserDetailResponse setTier(UUID userId, SubscriptionTier tier) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        if (tier == SubscriptionTier.PRO) {
            subscriptionService.activatePro(user, "manual", null, null);
        } else {
            subscriptionService.cancel(user);
        }
        log.info("admin.user.set_tier userId={} tier={}", userId, tier);
        return detail(user);
    }

    /**
     * Exclude (or re-include) a user from ALL admin metrics without deleting it.
     * Used for store-review / robo test accounts that would otherwise pollute the
     * funnel and subscription counts. Idempotent.
     */
    @Transactional
    public AdminUserDetailResponse setMetricsExclusion(UUID userId, boolean excluded) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        user.setExcludedFromMetrics(excluded);
        userRepository.save(user);
        log.info("admin.user.metrics_exclusion userId={} excluded={}", userId, excluded);
        return detail(user);
    }

    /**
     * Delete a user account and its dependents (test/garbage cleanup). Reuses
     * the self-service deletion cascade. Refuses ADMIN accounts — demote first
     * if one really has to go.
     */
    @Transactional
    public void delete(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        if (user.getRole() == Role.ADMIN) {
            throw new AdminUserDeletionException();
        }
        userService.deleteAccount(user);
        log.info("admin.user.delete userId={} email={}", userId, LogMasker.email(user.getEmail()));
    }

    private AdminUserDetailResponse detail(User user) {
        var householdId = user.getHousehold().getId();
        var receipts = countReceiptsByStatus(householdId);
        var spend = insightsRepository.totalSpend(
                householdId, LocalDateTime.now().minusDays(SPEND_WINDOW_DAYS), LocalDateTime.now());
        var memberCount = userRepository.countByHouseholdId(householdId);
        return AdminUserDetailResponse.from(user, memberCount, receipts, spend);
    }

    private ReceiptCounts countReceiptsByStatus(UUID householdId) {
        return new ReceiptCounts(
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.PENDING_CONFIRMATION),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.CONFIRMED),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.REJECTED),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.FAILED_PARSE)
        );
    }

    private Specification<User> searchSpec(String search) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (search != null) {
                var like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("name")), like)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
