package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import com.relyon.economizaai.service.report.PurchaseReportData.MarketSpend;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    void weekWithPurchases_rendersInTheUsersLocale() {
        stubReport(2, List.of(new CategorySpend("BEVERAGES", new BigDecimal("50.00"))),
                List.of(new MarketSpend("Mercado X", "12345678000190", new BigDecimal("150.00"), 2)));

        var english = service.buildWeeklyReport(userWithLocale("en")).text();
        var portuguese = service.buildWeeklyReport(userWithLocale("pt")).text();

        assertThat(english).contains("*Your Economiza AI summary*", "purchases", "Where it went:", "Beverages", "Stores:");
        assertThat(portuguese).contains("*Seu resumo Economiza AI*", "compras", "Onde foi:", "Bebidas", "Mercados:");
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
}
