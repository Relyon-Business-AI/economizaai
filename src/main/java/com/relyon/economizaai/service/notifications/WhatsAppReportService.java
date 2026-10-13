package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Renders a household's last-7-days spending into a short WhatsApp-friendly text
 * (header + totals + top categories + top markets). Used both for the on-demand
 * "pull" reply and as the body of the proactive weekly report.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppReportService {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");
    private static final int TOP_CATEGORIES = 3;
    private static final int TOP_MARKETS = 2;

    private final PurchaseReportAssembler reportAssembler;
    private final LocalizedMessageService messageService;

    /** A weekly report body plus whether it has category data worth charting. */
    public record WeeklyReport(String text, boolean hasCategoryData) {}

    @Transactional(readOnly = true)
    public String buildWeeklyReportText(User user) {
        return buildWeeklyReport(user).text();
    }

    @Transactional(readOnly = true)
    public WeeklyReport buildWeeklyReport(User user) {
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(7);
        var data = reportAssembler.assemble(user, from, to);
        var kpis = data.kpis();

        if (kpis.receiptCount() == 0) {
            return new WeeklyReport("📊 *Seu resumo economizai* (" + from.format(DAY_MONTH)
                    + " a " + to.format(DAY_MONTH) + ")\n\n"
                    + "Nenhuma compra registrada essa semana. Escaneie suas notas pra acompanhar seus gastos! 🛒",
                    false);
        }

        var text = new StringBuilder();
        text.append("📊 *Seu resumo economizai* (").append(from.format(DAY_MONTH))
                .append(" a ").append(to.format(DAY_MONTH)).append(")\n\n");
        text.append("💰 Total: ").append(money(kpis.totalSpent()))
                .append(" em ").append(kpis.receiptCount())
                .append(kpis.receiptCount() == 1 ? " compra" : " compras").append("\n");

        var categories = data.categoryBreakdown();
        if (!categories.isEmpty()) {
            text.append("\n🏷️ Onde foi:\n");
            categories.stream().limit(TOP_CATEGORIES).forEach(category ->
                    text.append("• ").append(categoryLabel(category.category()))
                            .append(": ").append(money(category.total())).append("\n"));
        }

        var markets = data.topMarkets();
        if (!markets.isEmpty()) {
            text.append("\n🛒 Mercados:\n");
            markets.stream().limit(TOP_MARKETS).forEach(market ->
                    text.append("• ").append(market.marketName() == null ? "—" : market.marketName())
                            .append(": ").append(money(market.total())).append("\n"));
        }

        return new WeeklyReport(text.toString().trim(), !categories.isEmpty());
    }

    /**
     * One-line summary for a PROACTIVE template variable ({{1}}) — WhatsApp forbids newlines/tabs
     * inside template variables, so the weekly push can't carry the multi-line body above.
     */
    @Transactional(readOnly = true)
    public String buildWeeklySummaryLine(User user) {
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(7);
        var kpis = reportAssembler.assemble(user, from, to).kpis();
        if (kpis.receiptCount() == 0) {
            return "Nenhuma compra registrada essa semana.";
        }
        return "Total " + money(kpis.totalSpent()) + " em " + kpis.receiptCount()
                + (kpis.receiptCount() == 1 ? " compra" : " compras") + " essa semana.";
    }

    private String categoryLabel(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return messageService.translate("category.OTHER");
        }
        return messageService.translate("category." + categoryName);
    }

    private static String money(BigDecimal value) {
        var amount = value == null ? BigDecimal.ZERO : value;
        return "R$ " + String.format("%,.2f", amount);
    }
}
