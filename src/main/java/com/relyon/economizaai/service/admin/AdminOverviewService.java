package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.dto.response.AdminOverviewResponse;
import com.relyon.economizaai.dto.response.AdminOverviewResponse.StateDistributionRow;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only cross-area KPI snapshot for the admin home. Reuses the existing count
 * queries per area; user counts exclude admins + test accounts (includeInternal=false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview(boolean includeInternal) {
        var today = BrazilClock.today();
        var startOfToday = today.atStartOfDay();
        var weekAgo = today.minusDays(6).atStartOfDay();
        var monthAgo = today.minusDays(29).atStartOfDay();

        var usersTotal = userRepository.countUsers(includeInternal);
        var usersToday = userRepository.countSignupsSince(startOfToday, includeInternal);
        var usersThisWeek = userRepository.countSignupsSince(weekAgo, includeInternal);
        var usersPro = userRepository.countProTier(includeInternal);
        var payingActive = subscriptionRepository.countPaying(SubscriptionStatus.ACTIVE, includeInternal);

        var receiptsTotal = receiptRepository.countReceipts(includeInternal);
        var receiptsToday = receiptRepository.countByCreatedAtGreaterThanEqual(startOfToday, includeInternal);
        var parseRate30d = parseRateSince(monthAgo, includeInternal);
        var dau = receiptRepository.countActiveHouseholdsSince(startOfToday, includeInternal);
        var wau = receiptRepository.countActiveHouseholdsSince(weekAgo, includeInternal);
        var mau = receiptRepository.countActiveHouseholdsSince(monthAgo, includeInternal);
        var stickiness = mau <= 0 ? 0d : BigDecimal.valueOf(dau)
                .divide(BigDecimal.valueOf(mau), 4, RoundingMode.HALF_UP).doubleValue();
        var totalSpend = scale(receiptRepository.sumConfirmedTotal(includeInternal));

        // The collaborative index is deliberately anonymized (PriceObservation carries no
        // user_id, households are shared) — its size stays a GLOBAL figure regardless of
        // the toggle, unlike the user/receipt-scoped KPIs above.
        var observations = priceObservationRepository.count();
        var contributingHouseholds = priceObservationAuditRepository.countDistinctContributingHouseholds();

        var byState = buildByState(includeInternal);

        log.info("admin.overview internal={} users={} today={} receipts={} parse30d={} dau={} wau={} mau={} spend={} obs={} ufs={}",
                includeInternal, usersTotal, usersToday, receiptsTotal, parseRate30d, dau, wau, mau, totalSpend, observations, byState.size());
        return new AdminOverviewResponse(usersTotal, usersToday, usersThisWeek, usersPro, payingActive,
                receiptsTotal, receiptsToday, parseRate30d, dau, wau, mau, stickiness, totalSpend,
                observations, contributingHouseholds, byState);
    }

    /**
     * Corte geográfico: funde notas+gasto por UF (confirmadas) com usuários por UF predominante.
     * A união das UFs das duas fontes garante que um estado com usuário mas sem nota confirmada
     * (ou vice-versa) ainda apareça. Ordenado por nº de notas desc.
     */
    private List<StateDistributionRow> buildByState(boolean includeInternal) {
        var merged = new LinkedHashMap<String, long[]>(); // uf -> [receipts, users]; spend à parte
        var spendByUf = new HashMap<String, BigDecimal>();
        for (var row : receiptRepository.confirmedReceiptsByUf(includeInternal)) {
            if (row[0] == null) continue;
            var uf = row[0].toString();
            merged.computeIfAbsent(uf, key -> new long[2])[0] = ((Number) row[1]).longValue();
            spendByUf.put(uf, scale((BigDecimal) row[2]));
        }
        for (var row : receiptRepository.usersByPredominantUf(includeInternal)) {
            if (row[0] == null) continue;
            merged.computeIfAbsent(row[0].toString(), key -> new long[2])[1] = ((Number) row[1]).longValue();
        }
        var rows = new ArrayList<StateDistributionRow>();
        for (var entry : merged.entrySet()) {
            rows.add(new StateDistributionRow(entry.getKey(), entry.getValue()[0],
                    spendByUf.getOrDefault(entry.getKey(), BigDecimal.ZERO.setScale(2)), entry.getValue()[1]));
        }
        rows.sort((left, right) -> Long.compare(right.receipts(), left.receipts()));
        return rows;
    }

    /** Parsed (confirmed/pending/rejected) ÷ (parsed + failed) over the window. */
    private double parseRateSince(LocalDateTime since, boolean includeInternal) {
        var byStatus = new EnumMap<ReceiptStatus, Long>(ReceiptStatus.class);
        for (var row : receiptRepository.statusBreakdownSince(since, includeInternal)) {
            byStatus.put((ReceiptStatus) row[0], ((Number) row[1]).longValue());
        }
        var parsed = count(byStatus, ReceiptStatus.CONFIRMED)
                + count(byStatus, ReceiptStatus.PENDING_CONFIRMATION)
                + count(byStatus, ReceiptStatus.REJECTED);
        var failed = count(byStatus, ReceiptStatus.FAILED_PARSE);
        var denominator = parsed + failed;
        return denominator <= 0 ? 0d : BigDecimal.valueOf(parsed)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static long count(Map<ReceiptStatus, Long> byStatus, ReceiptStatus status) {
        return byStatus.getOrDefault(status, 0L);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
