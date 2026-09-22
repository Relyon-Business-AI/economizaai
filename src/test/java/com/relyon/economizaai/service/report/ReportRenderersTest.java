package com.relyon.economizaai.service.report;

import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.ItemRow;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import com.relyon.economizaai.service.report.PurchaseReportData.MarketSpend;
import com.relyon.economizaai.service.report.PurchaseReportData.MonthlySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.ProductSpend;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ReportRenderersTest {

    @Mock private LocalizedMessageService localizedMessageService;

    private XlsxReportRenderer xlsxRenderer;
    private PdfReportRenderer pdfRenderer;

    @BeforeEach
    void setUp() {
        // labels echo their key — assertions stay locale-independent
        lenient().when(localizedMessageService.translate(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        xlsxRenderer = new XlsxReportRenderer(localizedMessageService);
        pdfRenderer = new PdfReportRenderer(localizedMessageService);
    }

    private PurchaseReportData sampleReport() {
        var items = List.of(
                new ItemRow(LocalDateTime.of(2026, Month.JUNE, 10, 10, 0), "Zaffari", "11111111000111",
                        "52260739346861022483650190000191021190388509", "ARROZ 5KG", BigDecimal.ONE, "UN",
                        new BigDecimal("28.90"), new BigDecimal("28.90"), "GROCERIES", new BigDecimal("38.90")),
                new ItemRow(LocalDateTime.of(2026, Month.JULY, 5, 18, 0), "Bistek", "22222222000122",
                        "52260739346861022483650190000191021190388509", "PICANHA KG", new BigDecimal("0.958"), "KG",
                        new BigDecimal("38.99"), new BigDecimal("37.35"), "MEAT_DAIRY", new BigDecimal("37.35")));
        return new PurchaseReportData(null, null,
                new Kpis(new BigDecimal("76.25"), 2, 2, new BigDecimal("38.13")),
                List.of(new MonthlySpend(YearMonth.of(2026, 6), new BigDecimal("28.90")),
                        new MonthlySpend(YearMonth.of(2026, 7), new BigDecimal("37.35"))),
                List.of(new CategorySpend("MEAT_DAIRY", new BigDecimal("37.35")),
                        new CategorySpend("GROCERIES", new BigDecimal("28.90"))),
                List.of(new MarketSpend("Zaffari", "11111111000111", new BigDecimal("38.90"), 1),
                        new MarketSpend("Bistek", "22222222000122", new BigDecimal("37.35"), 1)),
                List.of(new ProductSpend("PICANHA KG", new BigDecimal("37.35"), new BigDecimal("0.958"))),
                items);
    }

    private PurchaseReportData emptyReport() {
        return new PurchaseReportData(null, null,
                new Kpis(BigDecimal.ZERO, 0, 0, BigDecimal.ZERO),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void xlsx_hasSummaryDashboardChartsAndStyledItemTable() throws Exception {
        var bytes = xlsxRenderer.render(sampleReport());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("report.sheet.summary");
            assertThat(workbook.getSheetName(1)).isEqualTo("report.sheet.items");
            var summary = workbook.getSheetAt(0);
            assertThat(summary.getRow(1).getCell(1).getStringCellValue()).isEqualTo("report.title");
            assertThat(summary.getDrawingPatriarch().getCharts()).hasSize(3);
            var itemsSheet = workbook.getSheet("report.sheet.items");
            assertThat(itemsSheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("ARROZ 5KG");
            assertThat(itemsSheet.getRow(2).getCell(8).getNumericCellValue()).isEqualTo(37.35);
            assertThat(itemsSheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        }
    }

    @Test
    void xlsx_emptyReport_rendersWithoutCharts() throws Exception {
        var bytes = xlsxRenderer.render(emptyReport());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var summary = workbook.getSheet("report.sheet.summary");
            assertThat(summary.getDrawingPatriarch() == null
                    || summary.getDrawingPatriarch().getCharts().isEmpty()).isTrue();
        }
    }

    @Test
    void pdf_rendersValidDocumentWithContent() {
        var bytes = pdfRenderer.render(sampleReport());

        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(20_000); // charts embedded as images
    }

    @Test
    void pdf_emptyReport_stillRendersValidDocument() {
        var bytes = pdfRenderer.render(emptyReport());

        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }
}
