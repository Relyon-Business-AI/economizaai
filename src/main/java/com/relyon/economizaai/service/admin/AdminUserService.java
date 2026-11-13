package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.AdminUserDetailResponse;
import com.relyon.economizaai.dto.response.AdminUserDetailResponse.ReceiptCounts;
import com.relyon.economizaai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizaai.dto.response.UserCostResponse;
import com.relyon.economizaai.exception.AdminRoleChangeException;
import com.relyon.economizaai.exception.AdminUserDeletionException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository;
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
    private final MerchantAccessRepository merchantAccessRepository;
    private final PaidApiCallRepository paidApiCallRepository;

    /** Sort keys that rank by a per-household/per-user aggregate (not a User column) — handled in memory. */
    private static final Set<String> AGGREGATE_SORTS = Set.of("receiptCount", "totalSpend", "cost", "uf");
    private static final int AGGREGATE_SORT_CAP = 5000;

    /** Filtros da listagem de usuários — agrupados pra não estourar a assinatura. ufUserIds:
     *  null = sem filtro de UF; lista (possivelmente vazia) = ids cujo estado predominante bate. */
    private record UserFilters(String search, LocalDateTime createdFrom, LocalDateTime createdTo,
                               Platform platform, AcquisitionChannel channel, List<UUID> ufUserIds,
                               boolean includeInternal) {
    }

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> list(String search, LocalDateTime createdFrom, LocalDateTime createdTo,
                                               Platform platform, AcquisitionChannel channel, UnidadeFederativa uf,
                                               boolean includeInternal, Pageable pageable) {
        var trimmed = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var ufUserIds = uf == null ? null : receiptRepository.userIdsByPredominantUf(uf.name());
        var filters = new UserFilters(trimmed, createdFrom, createdTo, platform, channel, ufUserIds, includeInternal);
        var aggregateOrder = pageable.getSort().stream()
                .filter(order -> AGGREGATE_SORTS.contains(order.getProperty()))
                .findFirst().orElse(null);
        if (aggregateOrder != null) {
            return listRankedByAggregate(filters, pageable, aggregateOrder);
        }
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        var page = userRepository.findAll(searchSpec(filters), sortedPageable);
        var context = enrich(page.getContent());
        return page.map(user -> summaryFor(user, context));
    }

    /**
     * Ranking by nº de notas / valor total: these are per-household aggregates, not
     * User columns, so JPA can't sort by them. The user base is small (bounded by the
     * cap), so we load the matching users, enrich with the batched aggregates, sort in
     * memory and paginate manually — correct and simple at this scale.
     */
    private Page<AdminUserSummaryResponse> listRankedByAggregate(UserFilters filters,
                                                                 Pageable pageable, Sort.Order order) {
        var all = userRepository.findAll(searchSpec(filters),
                PageRequest.of(0, AGGREGATE_SORT_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        var context = enrich(all);
        var summaries = new ArrayList<>(all.stream().map(user -> summaryFor(user, context)).toList());

        var comparator = "uf".equals(order.getProperty())
                ? predominantUfComparator(all, order.isDescending())
                : aggregateValueComparator(all, order);
        summaries.sort(comparator.thenComparing(AdminUserSummaryResponse::createdAt, Comparator.reverseOrder()));

        var from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), summaries.size());
        var to = Math.min(from + pageable.getPageSize(), summaries.size());
        return new PageImpl<>(summaries.subList(from, to), pageable, summaries.size());
    }

    private Comparator<AdminUserSummaryResponse> aggregateValueComparator(List<User> users, Sort.Order order) {
        Comparator<AdminUserSummaryResponse> comparator;
        if ("cost".equals(order.getProperty())) {
            var costByUser = totalCostCentsByUserIds(users.stream().map(User::getId).toList());
            comparator = Comparator.comparingLong(summary -> costByUser.getOrDefault(summary.id(), 0L));
        } else if ("totalSpend".equals(order.getProperty())) {
            comparator = Comparator.comparing(AdminUserSummaryResponse::totalSpend);
        } else {
            comparator = Comparator.comparingLong(AdminUserSummaryResponse::receiptCount);
        }
        return order.isDescending() ? comparator.reversed() : comparator;
    }

    /** By the state the user scans most notes from (A→Z or Z→A); users with no state yet always come last. */
    private Comparator<AdminUserSummaryResponse> predominantUfComparator(List<User> users, boolean descending) {
        var ufByHousehold = predominantUfByHouseholdIds(householdIdsOf(users));
        Comparator<String> ufOrder = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(
                summary -> Optional.ofNullable(ufByHousehold.get(summary.householdId())).map(Enum::name).orElse(null),
                Comparator.nullsLast(ufOrder));
    }

    /**
     * Batched enrichment for a page of users: the USER-OWN receipt count + confirmed spend (keyed
     * by user_id), plus the HOUSEHOLD receipt count + member count (keyed by household_id) as
     * separate, clearly-labeled context. One query each — no N+1.
     */
    private ListContext enrich(List<User> users) {
        var userIds = users.stream().map(User::getId).toList();
        var householdIds = householdIdsOf(users);
        return new ListContext(
                countByUser(userIds),
                spendByUser(userIds),
                householdReceiptCounts(householdIds),
                householdMemberCounts(householdIds));
    }

    private record ListContext(Map<UUID, Long> userReceiptCounts, Map<UUID, BigDecimal> userSpend,
                               Map<UUID, Long> householdReceiptCounts, Map<UUID, Long> householdMemberCounts) {}

    private AdminUserSummaryResponse summaryFor(User user, ListContext context) {
        var householdId = householdIdOf(user);
        return AdminUserSummaryResponse.from(user,
                context.userReceiptCounts().getOrDefault(user.getId(), 0L),
                context.userSpend().getOrDefault(user.getId(), BigDecimal.ZERO),
                context.householdReceiptCounts().getOrDefault(householdId, 0L),
                context.householdMemberCounts().getOrDefault(householdId, 0L));
    }

    /** USER-OWN receipt count (receipts WHERE user_id = user), batched — "nº de notas" per user. */
    private Map<UUID, Long> countByUser(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        var counts = new HashMap<UUID, Long>();
        for (var row : receiptRepository.countByUserIds(userIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** USER-OWN confirmed spend, batched — "valor total" per user. */
    private Map<UUID, BigDecimal> spendByUser(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        var spend = new HashMap<UUID, BigDecimal>();
        for (var row : receiptRepository.sumConfirmedTotalByUserIds(userIds)) {
            spend.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return spend;
    }

    /** HOUSEHOLD receipt count (all members), batched — kept separate from the user's own count. */
    private Map<UUID, Long> householdReceiptCounts(List<UUID> householdIds) {
        if (householdIds.isEmpty()) return Map.of();
        var counts = new HashMap<UUID, Long>();
        for (var row : receiptRepository.countByHouseholdIds(householdIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** HOUSEHOLD member count, batched — powers the "Domicílio N" badge (shown when > 1). */
    private Map<UUID, Long> householdMemberCounts(List<UUID> householdIds) {
        if (householdIds.isEmpty()) return Map.of();
        var counts = new HashMap<UUID, Long>();
        for (var row : userRepository.countByHouseholdIds(householdIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Predominant UF (most-frequent state) per household — the state a user scans most of their
     * notes from. Batch + lazy: the admin list/detail loads WITHOUT this and fetches it separately,
     * so the heavy query never slows the main page. Households with no UF-bearing note are absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UnidadeFederativa> predominantUfByHouseholdIds(List<UUID> householdIds) {
        if (householdIds == null || householdIds.isEmpty()) {
            return Map.of();
        }
        var predominant = new HashMap<UUID, UnidadeFederativa>();
        var topCount = new HashMap<UUID, Long>();
        for (var row : receiptRepository.ufFrequencyByHouseholdIds(householdIds)) {
            var householdId = (UUID) row[0];
            var uf = (UnidadeFederativa) row[1];
            var count = ((Number) row[2]).longValue();
            if (count > topCount.getOrDefault(householdId, 0L)) {
                topCount.put(householdId, count);
                predominant.put(householdId, uf);
            }
        }
        return predominant;
    }

    /**
     * Lifetime paid-API cost (cents) per user — how much each user has cost us. Batch + lazy: the
     * admin list/detail loads WITHOUT it and fetches it separately, so the main page stays fast.
     * Users with no paid call are absent (treated as 0 by the caller).
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> totalCostCentsByUserIds(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        var costs = new HashMap<UUID, Long>();
        for (var row : paidApiCallRepository.totalCostByUserIds(userIds)) {
            costs.put(row.getUserId(), row.getCostCents());
        }
        return costs;
    }

    /** Where a single user's cost comes from — total + per-service breakdown + the last 12 months (lifetime). */
    @Transactional(readOnly = true)
    public UserCostResponse costBreakdownForUser(UUID userId) {
        var lines = paidApiCallRepository.costByServiceForUser(userId).stream()
                .map(row -> new UserCostResponse.ServiceLine(row.getService(), row.getCalls(), row.getCostCents()))
                .toList();
        var byMonth = paidApiCallRepository.costByMonthForUser(userId).stream()
                .limit(12)
                .map(row -> new UserCostResponse.MonthlyLine(row.getYearMonth(), row.getCalls(), row.getCostCents()))
                .toList();
        var total = lines.stream().mapToLong(UserCostResponse.ServiceLine::costCents).sum();
        return new UserCostResponse(total, lines, byMonth);
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
     * Change a user's role (USER ↔ MERCHANT). Anything touching ADMIN — demoting
     * an admin or promoting to admin — is refused; admin promotion stays a manual
     * DB operation. Demoting a MERCHANT revokes its chain grants so no stale
     * access survives a later re-promotion. Idempotent on same-role calls.
     */
    @Transactional
    public AdminUserDetailResponse setRole(UUID userId, Role role) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        if (user.getRole() == Role.ADMIN || role == Role.ADMIN) {
            throw new AdminRoleChangeException();
        }
        if (user.getRole() == Role.MERCHANT && role != Role.MERCHANT) {
            merchantAccessRepository.deleteAllByUserId(userId);
            log.info("admin.user.merchant_access_revoked_on_demotion userId={}", userId);
        }
        user.setRole(role);
        userRepository.save(user);
        log.info("admin.user.set_role userId={} role={}", userId, role);
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
        // USER-OWN: status breakdown + 30-day spend from THIS user's receipts.
        var receipts = countReceiptsByStatusForUser(user.getId());
        var spend = receiptRepository.sumConfirmedTotalByUserSince(
                user.getId(), LocalDateTime.now().minusDays(SPEND_WINDOW_DAYS));
        // HOUSEHOLD context (clearly separate): members + all-member receipt count + confirmed spend.
        var memberCount = userRepository.countByHouseholdId(householdId);
        var householdReceiptCount = receiptRepository.countByHouseholdId(householdId);
        var householdSpend = insightsRepository.totalSpend(
                householdId, LocalDateTime.now().minusDays(SPEND_WINDOW_DAYS), LocalDateTime.now(),
                "ALL", List.of("__none__"));
        return AdminUserDetailResponse.from(user, memberCount, receipts, spend,
                householdReceiptCount, householdSpend);
    }

    private ReceiptCounts countReceiptsByStatusForUser(UUID userId) {
        var byStatus = new HashMap<ReceiptStatus, Long>();
        for (var row : receiptRepository.statusBreakdownByUserId(userId)) {
            byStatus.put((ReceiptStatus) row[0], ((Number) row[1]).longValue());
        }
        return new ReceiptCounts(
                byStatus.getOrDefault(ReceiptStatus.PENDING_CONFIRMATION, 0L),
                byStatus.getOrDefault(ReceiptStatus.CONFIRMED, 0L),
                byStatus.getOrDefault(ReceiptStatus.REJECTED, 0L),
                byStatus.getOrDefault(ReceiptStatus.FAILED_PARSE, 0L)
        );
    }

    private Specification<User> searchSpec(UserFilters filters) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (filters.search() != null) {
                var like = "%" + filters.search().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("name")), like)
                ));
            }
            // Filtro de período por data de cadastro (Hoje / 7 dias / mês), igual às Notas.
            if (filters.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filters.createdFrom()));
            }
            if (filters.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filters.createdTo()));
            }
            // Plataforma de cadastro (iOS/Android/Web) e canal de aquisição (origem).
            if (filters.platform() != null) {
                predicates.add(cb.equal(root.get("registrationPlatform"), filters.platform()));
            }
            if (filters.channel() != null) {
                predicates.add(cb.equal(root.get("acquisitionChannel"), filters.channel()));
            }
            // Filtro por UF predominante: ufUserIds é o conjunto de ids cujo estado-moda bate.
            // Lista vazia = ninguém naquela UF → nega tudo (senão o filtro seria ignorado).
            if (filters.ufUserIds() != null) {
                predicates.add(filters.ufUserIds().isEmpty()
                        ? cb.disjunction()
                        : root.get("id").in(filters.ufUserIds()));
            }
            // Off by default: hide admins / test accounts / metrics-excluded from the list.
            if (!filters.includeInternal()) {
                predicates.add(cb.notEqual(root.get("role"), Role.ADMIN));
                predicates.add(cb.isFalse(root.get("excludedFromMetrics")));
                predicates.add(cb.notLike(cb.lower(root.get("email")), "%@economizaai.app"));
                predicates.add(cb.notLike(cb.lower(root.get("email")), "%@cloudtestlabaccounts.com"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
