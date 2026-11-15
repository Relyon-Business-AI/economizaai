package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Renders a household's spending into a short WhatsApp-friendly text (header + totals + top
 * categories + top markets). The last 7 days serve the on-demand "pull" reply and the
 * proactive weekly report; the previous calendar month serves the monthly report.
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

    /**
     * The weekly report as a notice: a plain-text body for the inbox and the e-mail, plus the
     * one-line summary a WhatsApp template variable can carry. {@code hasPurchases} is false
     * for a week without receipts — there is nothing worth sending then.
     */
    public record WeeklyReportNotice(String body, String summaryLine, boolean hasPurchases) {}

    @Transactional(readOnly = true)
    public WeeklyReportNotice buildWeeklyReportNotice(User user) {
        var notice = lastSevenDaysNotice(user);
        return new WeeklyReportNotice(notice.body(), notice.summaryLine(), notice.hasPurchases());
    }

    /**
     * The spending report of one period as a notice: the title, a plain-text body for the inbox
     * and the e-mail, plus the one-line summary a WhatsApp template variable can carry. Every
     * text names the period it covers. {@code hasPurchases} is false for a period without
     * receipts — there is nothing worth sending then.
     */
    public record SpendingReportNotice(String title, String body, String summaryLine, boolean hasPurchases) {}

    /** WEEKLY covers the last seven days; MONTHLY the previous full calendar month (Brasília time). */
    @Transactional(readOnly = true)
    public SpendingReportNotice buildSpendingReportNotice(User user, SpendingReportFrequency frequency) {
        return switch (frequency) {
            case WEEKLY -> lastSevenDaysNotice(user);
            case MONTHLY -> previousMonthNotice(user);
        };
    }

    private SpendingReportNotice lastSevenDaysNotice(User user) {
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(7);
        var data = reportAssembler.assemble(user, from, to);
        var kpis = data.kpis();
        var title = messageService.translate("notification.weekly_report.title", locale);
        if (kpis.receiptCount() == 0) {
            return new SpendingReportNotice(title, messageService.translate("whatsapp.report.empty", locale),
                    messageService.translate("whatsapp.report.summary.empty", locale), false);
        }
        var period = messageService.translate("notification.weekly_report.period", locale,
                from.format(DAY_MONTH), to.format(DAY_MONTH));
        var summaryLine = messageService.translate("whatsapp.report.summary", locale,
                amount(kpis.totalSpent()), String.valueOf(kpis.receiptCount()));
        return new SpendingReportNotice(title, period + "\n\n" + spendingBreakdown(data, locale), summaryLine, true);
    }

    private SpendingReportNotice previousMonthNotice(User user) {
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var month = BrazilClock.currentYearMonth().minusMonths(1);
        var from = month.atDay(1).atStartOfDay();
        var to = month.plusMonths(1).atDay(1).atStartOfDay().minusNanos(1);
        var data = reportAssembler.assemble(user, from, to);
        var kpis = data.kpis();
        var monthName = month.getMonth().getDisplayName(TextStyle.FULL, locale);
        var title = messageService.translate("notification.monthly_report.title", locale, monthName);
        if (kpis.receiptCount() == 0) {
            return new SpendingReportNotice(title,
                    messageService.translate("whatsapp.report.monthly.empty", locale, monthName),
                    messageService.translate("whatsapp.report.monthly.summary.empty", locale, monthName), false);
        }
        var period = messageService.translate("notification.monthly_report.period", locale,
                monthName, String.valueOf(month.getYear()));
        var summaryLine = messageService.translate("whatsapp.report.monthly.summary", locale,
                amount(kpis.totalSpent()), String.valueOf(kpis.receiptCount()), monthName);
        return new SpendingReportNotice(title, period + "\n\n" + spendingBreakdown(data, locale), summaryLine, true);
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
        return new WeeklyReport(header + "\n\n" + spendingBreakdown(data, locale), !data.categoryBreakdown().isEmpty());
    }

    /** Total, top categories and top markets of a period that has purchases. */
    private String spendingBreakdown(PurchaseReportData data, Locale locale) {
        var kpis = data.kpis();
        var text = new StringBuilder();
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

        return text.toString().trim();
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
        return NotificationTextFormatter.money(value);
    }

    private static String amount(BigDecimal value) {
        return NotificationTextFormatter.amount(value);
    }
}
