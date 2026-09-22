package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.AdminUserDetailResponse;
import com.relyon.economizaai.dto.response.AdminUserDetailResponse.ReceiptCounts;
import com.relyon.economizaai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizaai.exception.AdminUserDeletionException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /** Sort keys that rank by a per-household aggregate (not a User column) — handled in memory. */
    private static final Set<String> AGGREGATE_SORTS = Set.of("receiptCount", "totalSpend");
    private static final int AGGREGATE_SORT_CAP = 5000;

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> list(String search, boolean includeInternal, Pageable pageable) {
        var trimmed = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var aggregateOrder = pageable.getSort().stream()
                .filter(order -> AGGREGATE_SORTS.contains(order.getProperty()))
                .findFirst().orElse(null);
        if (aggregateOrder != null) {
            return listRankedByAggregate(trimmed, includeInternal, pageable, aggregateOrder);
        }
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        var page = userRepository.findAll(searchSpec(trimmed, includeInternal), sortedPageable);
        var counts = receiptCountsFor(page.getContent());
        var spend = spendFor(page.getContent());
        return page.map(user -> summaryFor(user, counts, spend));
    }

    /**
     * Ranking by nº de notas / valor total: these are per-household aggregates, not
     * User columns, so JPA can't sort by them. The user base is small (bounded by the
     * cap), so we load the matching users, enrich with the batched aggregates, sort in
     * memory and paginate manually — correct and simple at this scale.
     */
    private Page<AdminUserSummaryResponse> listRankedByAggregate(String search, boolean includeInternal,
                                                                 Pageable pageable, Sort.Order order) {
        var all = userRepository.findAll(searchSpec(search, includeInternal),
                PageRequest.of(0, AGGREGATE_SORT_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        var counts = receiptCountsFor(all);
        var spend = spendFor(all);
        var summaries = new ArrayList<>(all.stream().map(user -> summaryFor(user, counts, spend)).toList());

        Comparator<AdminUserSummaryResponse> comparator = "totalSpend".equals(order.getProperty())
                ? Comparator.comparing(AdminUserSummaryResponse::totalSpend)
                : Comparator.comparingLong(AdminUserSummaryResponse::receiptCount);
        if (order.isDescending()) {
            comparator = comparator.reversed();
        }
        summaries.sort(comparator.thenComparing(AdminUserSummaryResponse::createdAt, Comparator.reverseOrder()));

        var from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), summaries.size());
        var to = Math.min(from + pageable.getPageSize(), summaries.size());
        return new PageImpl<>(summaries.subList(from, to), pageable, summaries.size());
    }

    private AdminUserSummaryResponse summaryFor(User user, Map<UUID, Long> counts, Map<UUID, BigDecimal> spend) {
        var householdId = householdIdOf(user);
        return AdminUserSummaryResponse.from(user,
                counts.getOrDefault(householdId, 0L),
                spend.getOrDefault(householdId, BigDecimal.ZERO));
    }

    /** One batched count query for the page's households — avoids N+1 while showing "nº de notas" per user. */
    private Map<UUID, Long> receiptCountsFor(List<User> users) {
        var householdIds = householdIdsOf(users);
        if (householdIds.isEmpty()) {
            return Map.of();
        }
        var counts = new HashMap<UUID, Long>();
        for (var row : receiptRepository.countByHouseholdIds(householdIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** One batched sum query for the page's households — "valor total" (confirmed spend) per user. */
    private Map<UUID, BigDecimal> spendFor(List<User> users) {
        var householdIds = householdIdsOf(users);
        if (householdIds.isEmpty()) {
            return Map.of();
        }
        var spend = new HashMap<UUID, BigDecimal>();
        for (var row : receiptRepository.sumConfirmedTotalByHouseholdIds(householdIds)) {
            spend.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return spend;
    }

    private List<UUID> householdIdsOf(List<User> users) {
        return users.stream().map(this::householdIdOf).filter(Objects::nonNull).distinct().toList();
    }

    private UUID householdIdOf(User user) {
        return user.getHousehold() == null ? null : user.getHousehold().getId();
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

    private Specification<User> searchSpec(String search, boolean includeInternal) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (search != null) {
                var like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("name")), like)
                ));
            }
            // Off by default: hide admins / test accounts / metrics-excluded from the list.
            if (!includeInternal) {
                predicates.add(cb.notEqual(root.get("role"), Role.ADMIN));
                predicates.add(cb.isFalse(root.get("excludedFromMetrics")));
                predicates.add(cb.notLike(cb.lower(root.get("email")), "%@economizaai.app"));
                predicates.add(cb.notLike(cb.lower(root.get("email")), "%@cloudtestlabaccounts.com"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
