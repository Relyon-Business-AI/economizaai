package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import com.relyon.economizaai.service.report.PurchaseReportData.MarketSpend;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The WhatsApp report text is fully localized in the RECIPIENT's stored locale. */
class WhatsAppReportServiceTest {

    private final PurchaseReportAssembler reportAssembler = mock(PurchaseReportAssembler.class);
    private final WhatsAppReportService service =
            new WhatsAppReportService(reportAssembler, new LocalizedMessageService(messageSource()));

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private static User userWithLocale(String locale) {
        var user = User.builder().email("whatsapp-report@economizaai.app").build();
        user.setLocale(locale);
        return user;
    }

    private void stubReport(int receiptCount, List<CategorySpend> categories, List<MarketSpend> markets) {
        var kpis = new Kpis(new BigDecimal("150.00"), receiptCount, 10, BigDecimal.ZERO);
        when(reportAssembler.assemble(any(), any(), any())).thenReturn(new PurchaseReportData(
                LocalDateTime.now(), LocalDateTime.now(), kpis, List.of(), categories, markets, List.of(), List.of()));
    }

    @Test
    void emptyWeek_ptUsesTheBrandAndNoEnglish() {
        stubReport(0, List.of(), List.of());

        var report = service.buildWeeklyReport(userWithLocale("pt"));

        assertThat(report.text()).contains("*Seu resumo Economiza AI*")
                .contains("Nenhuma nota essa semana.")
                .doesNotContain("economizai");
        assertThat(report.hasCategoryData()).isFalse();
    }

    @Test
    void weekWithPurchases_isAlwaysPortugueseWithBrazilianMoney() {
        stubReport(2, List.of(new CategorySpend("BEVERAGES", new BigDecimal("50.00"))),
                List.of(new MarketSpend("Mercado X", "12345678000190", new BigDecimal("150.00"), 2)));

        var english = service.buildWeeklyReport(userWithLocale("en")).text();
        var portuguese = service.buildWeeklyReport(userWithLocale("pt")).text();

        assertThat(portuguese).contains("*Seu resumo Economiza AI*", "compras", "Onde foi:", "Bebidas", "Mercados:",
                "R$ 150,00");
        // No language selector in the app: a device-captured "en" still gets Portuguese.
        assertThat(english).isEqualTo(portuguese);
    }

    @Test
    void summaryLine_singlePurchaseAndEmptyWeek() {
        stubReport(0, List.of(), List.of());
        assertThat(service.buildWeeklySummaryLine(userWithLocale("pt"))).isEqualTo("Nenhuma nota essa semana.");

        stubReport(1, List.of(), List.of());
        assertThat(service.buildWeeklySummaryLine(userWithLocale("pt")))
                .startsWith("R$ ")
                .endsWith("em 1 compra(s) essa semana.");
    }

    @Test
    void weeklyReportNotice_isPlainTextForEmailWithAOneLineSummary() {
        stubReport(2, List.of(new CategorySpend("BEVERAGES", new BigDecimal("50.00"))),
                List.of(new MarketSpend("Mercado X", "12345678000190", new BigDecimal("150.00"), 2)));

        var notice = service.buildWeeklyReportNotice(userWithLocale("pt"));

        assertThat(notice.hasPurchases()).isTrue();
        assertThat(notice.body()).startsWith("Seus gastos de ")
                .contains("R$ 150,00", "2 compras", "Onde foi:", "Bebidas", "Mercados:", "Mercado X")
                .doesNotContain("*");
        assertThat(notice.summaryLine()).isEqualTo("R$ 150,00 em 2 compra(s) essa semana.").doesNotContain("\n");
    }

    @Test
    void weeklySpendingReportNotice_matchesTheWeeklyNoticeAndCarriesTheTitle() {
        stubReport(2, List.of(), List.of());
        var user = userWithLocale("pt");

        var notice = service.buildSpendingReportNotice(user, SpendingReportFrequency.WEEKLY);
        var weeklyNotice = service.buildWeeklyReportNotice(user);

        assertThat(notice.title()).isEqualTo("Seu resumo da semana");
        assertThat(notice.summaryLine()).isEqualTo(weeklyNotice.summaryLine());
        assertThat(notice.hasPurchases()).isTrue();
        assertThat(notice.body()).startsWith("Seus gastos de ").contains(" a ");
    }

    @Test
    void monthlySpendingReportNotice_coversThePreviousFullCalendarMonthAndNamesIt() {
        stubReport(2, List.of(new CategorySpend("BEVERAGES", new BigDecimal("50.00"))),
                List.of(new MarketSpend("Mercado X", "12345678000190", new BigDecimal("150.00"), 2)));
        var previousMonth = BrazilClock.currentYearMonth().minusMonths(1);
        var monthName = previousMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"));

        var notice = service.buildSpendingReportNotice(userWithLocale("pt"), SpendingReportFrequency.MONTHLY);

        var from = ArgumentCaptor.forClass(LocalDateTime.class);
        var to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportAssembler).assemble(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(previousMonth.atDay(1).atStartOfDay());
        assertThat(to.getValue()).isEqualTo(previousMonth.atEndOfMonth().atTime(LocalTime.MAX));
        assertThat(notice.hasPurchases()).isTrue();
        assertThat(notice.title()).isEqualTo("Seu resumo de " + monthName);
        assertThat(notice.body())
                .startsWith("Seus gastos de " + monthName + " de " + previousMonth.getYear())
                .contains("R$ 150,00", "2 compras", "Onde foi:", "Bebidas", "Mercados:", "Mercado X")
                .doesNotContain("*", "semana");
        assertThat(notice.summaryLine()).isEqualTo("R$ 150,00 em 2 compra(s) em " + monthName + ".")
                .doesNotContain("\n");
    }

    @Test
    void monthlySpendingReportNotice_ofAMonthWithoutReceiptsHasNothingToSend() {
        stubReport(0, List.of(), List.of());

        var notice = service.buildSpendingReportNotice(userWithLocale("pt"), SpendingReportFrequency.MONTHLY);

        assertThat(notice.hasPurchases()).isFalse();
        assertThat(notice.summaryLine()).startsWith("Nenhuma nota em ").doesNotContain("semana");
    }

    @Test
    void weeklyReportNotice_ofAWeekWithoutReceiptsHasNothingToSend() {
        stubReport(0, List.of(), List.of());

        var notice = service.buildWeeklyReportNotice(userWithLocale("pt"));

        assertThat(notice.hasPurchases()).isFalse();
        assertThat(notice.summaryLine()).isEqualTo("Nenhuma nota essa semana.");
    }
}
