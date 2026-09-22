package com.relyon.economizaai.service.report;

import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The "beautiful sheet": a multi-tab XLSX with a summary dashboard (KPIs +
 * three native Excel charts — monthly evolution, category breakdown, top
 * markets), a styled item table, and the aggregate tabs the charts read from.
 * Native XDDF charts stay interactive/editable in Excel and Google Sheets.
 * All labels localized via Accept-Language.
 */
@Component
@RequiredArgsConstructor
public class XlsxReportRenderer {

    private static final DateTimeFormatter CELL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final byte[] BRAND_GREEN = {(byte) 0x2E, (byte) 0x7D, (byte) 0x32};
    private static final byte[] LIGHT_GREEN = {(byte) 0xE8, (byte) 0xF5, (byte) 0xE9};

    private final LocalizedMessageService localizedMessageService;

    public byte[] render(PurchaseReportData report) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var styles = new Styles(workbook);
            var monthlySheet = aggregatesSheet(workbook, styles, translate("report.sheet.monthly"),
                    List.of(translate("report.chart.month"), translate("report.chart.total")),
                    report.monthlySeries().stream()
                            .map(monthly -> List.<Object>of(MONTH_LABEL.format(monthly.month()), monthly.total()))
                            .toList());
            var categorySheet = aggregatesSheet(workbook, styles, translate("report.sheet.categories"),
                    List.of(translate("export.header.category"), translate("report.chart.total")),
                    report.categoryBreakdown().stream()
                            .map(category -> List.<Object>of(categoryLabel(category.category()), category.total()))
                            .toList());
            var marketSheet = aggregatesSheet(workbook, styles, translate("report.sheet.markets"),
                    List.of(translate("export.header.market"), translate("report.chart.total")),
                    report.topMarkets().stream()
                            .map(market -> List.<Object>of(displayMarket(market.marketName()), market.total()))
                            .toList());
            summarySheet(workbook, styles, report, monthlySheet, categorySheet, marketSheet);
            itemsSheet(workbook, styles, report);
            workbook.setSheetOrder(translate("report.sheet.summary"), 0);
            workbook.setSheetOrder(translate("report.sheet.items"), 1);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build XLSX report", ex);
        }
    }

    // ---------- summary dashboard ----------

    private void summarySheet(XSSFWorkbook workbook, Styles styles, PurchaseReportData report,
                              XSSFSheet monthlySheet, XSSFSheet categorySheet, XSSFSheet marketSheet) {
        var sheet = workbook.createSheet(translate("report.sheet.summary"));
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 2 * 256);
        for (var column = 1; column <= 8; column++) sheet.setColumnWidth(column, 18 * 256);

        var title = sheet.createRow(1).createCell(1);
        title.setCellValue(translate("report.title"));
        title.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 1, 6));

        kpiBlock(sheet, styles, 3, 1, translate("report.kpi.total-spent"), money(report.kpis().totalSpent()));
        kpiBlock(sheet, styles, 3, 3, translate("report.kpi.receipts"), String.valueOf(report.kpis().receiptCount()));
        kpiBlock(sheet, styles, 3, 5, translate("report.kpi.items"), String.valueOf(report.kpis().itemCount()));
        kpiBlock(sheet, styles, 3, 7, translate("report.kpi.average-ticket"), money(report.kpis().averageTicket()));

        if (!report.monthlySeries().isEmpty()) {
            chart(sheet, monthlySheet, ChartTypes.LINE, translate("report.chart.monthly-title"),
                    report.monthlySeries().size(), 1, 7, 9, 22);
        }
        if (!report.categoryBreakdown().isEmpty()) {
            chart(sheet, categorySheet, ChartTypes.PIE, translate("report.chart.categories-title"),
                    report.categoryBreakdown().size(), 1, 24, 4, 39);
        }
        if (!report.topMarkets().isEmpty()) {
            chart(sheet, marketSheet, ChartTypes.BAR, translate("report.chart.markets-title"),
                    report.topMarkets().size(), 5, 24, 9, 39);
        }
    }

    private void kpiBlock(XSSFSheet sheet, Styles styles, int rowIndex, int columnIndex,
                          String label, String value) {
        var labelRow = sheet.getRow(rowIndex) != null ? sheet.getRow(rowIndex) : sheet.createRow(rowIndex);
        var labelCell = labelRow.createCell(columnIndex);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(styles.kpiLabel);
        var valueRow = sheet.getRow(rowIndex + 1) != null ? sheet.getRow(rowIndex + 1) : sheet.createRow(rowIndex + 1);
        var valueCell = valueRow.createCell(columnIndex);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(styles.kpiValue);
    }

    private void chart(XSSFSheet summarySheet, XSSFSheet dataSheet, ChartTypes type, String chartTitle,
                       int seriesLength, int fromColumn, int fromRow, int toColumn, int toRow) {
        var drawing = summarySheet.createDrawingPatriarch();
        var anchor = new XSSFClientAnchor();
        anchor.setCol1(fromColumn);
        anchor.setRow1(fromRow);
        anchor.setCol2(toColumn + 1);
        anchor.setRow2(toRow);
        var chart = drawing.createChart(anchor);
        chart.setTitleText(chartTitle);
        chart.setTitleOverlay(false);
        var legend = chart.getOrAddLegend();
        legend.setPosition(type == ChartTypes.PIE ? LegendPosition.RIGHT : LegendPosition.BOTTOM);

        var labels = XDDFDataSourcesFactory.fromStringCellRange(dataSheet,
                new CellRangeAddress(1, seriesLength, 0, 0));
        var values = XDDFDataSourcesFactory.fromNumericCellRange(dataSheet,
                new CellRangeAddress(1, seriesLength, 1, 1));
        var data = type == ChartTypes.PIE
                ? chart.createData(ChartTypes.PIE, null, null)
                : chart.createData(type,
                        chart.createCategoryAxis(AxisPosition.BOTTOM),
                        chart.createValueAxis(AxisPosition.LEFT));
        var series = data.addSeries(labels, values);
        series.setTitle(chartTitle, null);
        chart.plot(data);
    }

    // ---------- data tabs ----------

    private XSSFSheet aggregatesSheet(XSSFWorkbook workbook, Styles styles, String name,
                                      List<String> headers, List<List<Object>> rows) {
        var sheet = workbook.createSheet(name);
        var headerRow = sheet.createRow(0);
        for (var column = 0; column < headers.size(); column++) {
            var cell = headerRow.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.tableHeader);
        }
        var rowIndex = 1;
        for (var row : rows) {
            var sheetRow = sheet.createRow(rowIndex++);
            for (var column = 0; column < row.size(); column++) {
                var cell = sheetRow.createCell(column);
                if (row.get(column) instanceof BigDecimal decimal) {
                    cell.setCellValue(decimal.doubleValue());
                    cell.setCellStyle(styles.money);
                } else {
                    cell.setCellValue(String.valueOf(row.get(column)));
                }
            }
        }
        for (var column = 0; column < headers.size(); column++) sheet.autoSizeColumn(column);
        return sheet;
    }

    private void itemsSheet(XSSFWorkbook workbook, Styles styles, PurchaseReportData report) {
        var sheet = workbook.createSheet(translate("report.sheet.items"));
        var headers = List.of(
                translate("export.header.date"), translate("export.header.market"),
                translate("export.header.market-cnpj"), translate("export.header.chave-acesso"),
                translate("export.header.item"), translate("export.header.quantity"),
                translate("export.header.unit"), translate("export.header.unit-price"),
                translate("export.header.item-total"), translate("export.header.category"),
                translate("export.header.receipt-total"));
        var headerRow = sheet.createRow(0);
        for (var column = 0; column < headers.size(); column++) {
            var cell = headerRow.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.tableHeader);
        }
        var rowIndex = 1;
        for (var item : report.items()) {
            var row = sheet.createRow(rowIndex++);
            var column = 0;
            setText(row.createCell(column++), item.issuedAt() == null ? "" : CELL_DATE.format(item.issuedAt()));
            setText(row.createCell(column++), item.market());
            setText(row.createCell(column++), item.marketCnpj());
            setText(row.createCell(column++), item.chaveAcesso());
            setText(row.createCell(column++), item.item());
            setNumber(row.createCell(column++), item.quantity(), styles.quantity);
            setText(row.createCell(column++), item.unit());
            setNumber(row.createCell(column++), item.unitPrice(), styles.money);
            setNumber(row.createCell(column++), item.itemTotal(), styles.money);
            setText(row.createCell(column++), categoryLabel(item.category()));
            setNumber(row.createCell(column), item.receiptTotal(), styles.money);
        }
        sheet.createFreezePane(0, 1);
        if (!report.items().isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(0, report.items().size(), 0, headers.size() - 1));
        }
        for (var column = 0; column < headers.size(); column++) sheet.autoSizeColumn(column);
    }

    private static void setText(Cell cell, String value) {
        cell.setCellValue(value == null ? "" : value);
    }

    private static void setNumber(Cell cell, BigDecimal value, XSSFCellStyle style) {
        if (value == null) return;
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private String categoryLabel(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return translate("category.OTHER");
        return translate("category." + categoryName);
    }

    private String displayMarket(String marketName) {
        return marketName == null || marketName.isBlank() ? "-" : marketName;
    }

    private String money(BigDecimal value) {
        return "R$ " + (value == null ? "0,00" : value.setScale(2, RoundingMode.HALF_UP)
                .toPlainString().replace('.', ','));
    }

    private String translate(String key) {
        return localizedMessageService.translate(key);
    }

    /** One-stop cell styles, created once per workbook. */
    private static final class Styles {
        final XSSFCellStyle title;
        final XSSFCellStyle kpiLabel;
        final XSSFCellStyle kpiValue;
        final XSSFCellStyle tableHeader;
        final XSSFCellStyle money;
        final XSSFCellStyle quantity;

        Styles(XSSFWorkbook workbook) {
            var brandGreen = new XSSFColor(BRAND_GREEN, null);
            var lightGreen = new XSSFColor(LIGHT_GREEN, null);

            var titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 18);
            titleFont.setColor(brandGreen);
            title = workbook.createCellStyle();
            title.setFont(titleFont);

            var kpiLabelFont = workbook.createFont();
            kpiLabelFont.setBold(true);
            kpiLabelFont.setFontHeightInPoints((short) 10);
            kpiLabel = workbook.createCellStyle();
            kpiLabel.setFont(kpiLabelFont);
            kpiLabel.setFillForegroundColor(lightGreen);
            kpiLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            var kpiValueFont = workbook.createFont();
            kpiValueFont.setBold(true);
            kpiValueFont.setFontHeightInPoints((short) 14);
            kpiValue = workbook.createCellStyle();
            kpiValue.setFont(kpiValueFont);

            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(whiteFont(workbook));
            tableHeader = workbook.createCellStyle();
            tableHeader.setFont(headerFont);
            tableHeader.setFillForegroundColor(brandGreen);
            tableHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            tableHeader.setAlignment(HorizontalAlignment.CENTER);
            tableHeader.setBorderBottom(BorderStyle.THIN);

            money = numberStyle(workbook, "#,##0.00");
            quantity = numberStyle(workbook, "#,##0.000");
        }

        private static short whiteFont(XSSFWorkbook workbook) {
            return IndexedColors.WHITE.getIndex();
        }

        private static XSSFCellStyle numberStyle(XSSFWorkbook workbook, String pattern) {
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(pattern));
            return style;
        }
    }
}
