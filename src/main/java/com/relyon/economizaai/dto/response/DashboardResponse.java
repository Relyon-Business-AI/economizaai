package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One-shot snapshot for the FE app-open screen. Bundles together the
 * pieces of data the home screen needs so the FE doesn't pay 5+
 * round-trips on cold-start. Each section is best-effort: when a section
 * has no data (new user, opted out, k-anon blocks), it returns
 * empty/zero — never errors.
 *
 * @param currentMonth                    spend snapshot for the current calendar month
 * @param recentReceipts                  last N confirmed receipts (newest first)
 * @param suggestedShoppingList           consumption-cadence suggestions (RAN_OUT + RUNNING_LOW)
 * @param communityPromosNearby           live community promos in user's area (radius-filtered if home set)
 * @param unreadNotificationCount         badge count for the bell icon
 * @param generatedAt                     when this snapshot was computed (for "x min ago" hints)
 */
public record DashboardResponse(
        SpendSnapshot currentMonth,
        List<ReceiptSummaryResponse> recentReceipts,
        List<ConsumptionPredictionResponse> suggestedShoppingList,
        List<CommunityPromo> communityPromosNearby,
        long unreadNotificationCount,
        LocalDateTime generatedAt
) {
    public record SpendSnapshot(
            int year,
            int month,
            BigDecimal total,
            BigDecimal discount,
            long receiptCount,
            BigDecimal averageTicket
    ) {}
}
