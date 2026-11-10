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
import java.util.Locale;

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
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(7);
        var data = reportAssembler.assemble(user, from, to);
        var kpis = data.kpis();
        var header = messageService.translate("whatsapp.report.header", locale,
                from.format(DAY_MONTH), to.format(DAY_MONTH));

        if (kpis.receiptCount() == 0) {
            return new WeeklyReport(header + "\n\n" + messageService.translate("whatsapp.report.empty", locale), false);
        }

        var text = new StringBuilder();
        text.append(header).append("\n\n");
        text.append("💰 ").append(messageService.translate("whatsapp.report.total", locale,
                money(kpis.totalSpent()), String.valueOf(kpis.receiptCount()),
                purchaseWord(kpis.receiptCount(), locale))).append("\n");

        var categories = data.categoryBreakdown();
        if (!categories.isEmpty()) {
            text.append("\n🏷️ ").append(messageService.translate("whatsapp.report.categories-label", locale)).append("\n");
            categories.stream().limit(TOP_CATEGORIES).forEach(category ->
                    text.append("• ").append(categoryLabel(category.category(), locale))
                            .append(": ").append(money(category.total())).append("\n"));
        }

        var markets = data.topMarkets();
        if (!markets.isEmpty()) {
            text.append("\n🛒 ").append(messageService.translate("whatsapp.report.markets-label", locale)).append("\n");
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
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(7);
        var kpis = reportAssembler.assemble(user, from, to).kpis();
        if (kpis.receiptCount() == 0) {
            return messageService.translate("whatsapp.report.summary.empty", locale);
        }
        return messageService.translate("whatsapp.report.summary", locale,
                amount(kpis.totalSpent()), String.valueOf(kpis.receiptCount()));
    }

    private String purchaseWord(long receiptCount, Locale locale) {
        return messageService.translate(receiptCount == 1 ? "whatsapp.report.purchase.one" : "whatsapp.report.purchase.many", locale);
    }

    private String categoryLabel(String categoryName, Locale locale) {
        if (categoryName == null || categoryName.isBlank()) {
            return messageService.translate("category.OTHER", locale);
        }
        return messageService.translate("category." + categoryName, locale);
    }

    private static String money(BigDecimal value) {
        return "R$ " + amount(value);
    }

    private static String amount(BigDecimal value) {
        return String.format("%,.2f", value == null ? BigDecimal.ZERO : value);
    }
}
