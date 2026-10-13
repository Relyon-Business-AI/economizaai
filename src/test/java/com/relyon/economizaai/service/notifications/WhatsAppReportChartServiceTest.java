package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhatsAppReportChartServiceTest {

    private final LocalizedMessageService messageService = mock(LocalizedMessageService.class);
    private final WhatsAppReportChartService service = new WhatsAppReportChartService(messageService);

    private PurchaseReportData reportWith(List<CategorySpend> categories) {
        return new PurchaseReportData(
                LocalDateTime.now().minusDays(7), LocalDateTime.now(),
                new Kpis(BigDecimal.TEN, 1, 3, BigDecimal.TEN),
                List.of(), categories, List.of(), List.of(), List.of());
    }

    @Test
    void renderCategoryPie_returnsNullWhenNoCategories() {
        assertNull(service.renderCategoryPie(reportWith(List.of()), Locale.forLanguageTag("pt")));
    }

    @Test
    void renderCategoryPie_returnsPngBytesWhenThereIsData() {
        when(messageService.translate(anyString(), any(Locale.class))).thenAnswer(inv -> inv.getArgument(0));
        var report = reportWith(List.of(
                new CategorySpend("HORTIFRUTI", new BigDecimal("41.72")),
                new CategorySpend("ACOUGUE", new BigDecimal("38.57")),
                new CategorySpend("MERCEARIA", new BigDecimal("88.49"))));

        var png = service.renderCategoryPie(report, Locale.forLanguageTag("pt"));

        assertNotNull(png);
        assertTrue(png.length > 100, "should produce a non-trivial PNG");
        // PNG magic number: 0x89 'P' 'N' 'G'
        assertTrue((png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G',
                "bytes should carry the PNG signature");
    }
}
