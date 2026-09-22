package com.relyon.economizaai.service.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The "pretty PDF": branded A4 purchase report — title band, KPI cards,
 * three charts (monthly evolution line, category donut, top-markets bar,
 * rendered via JFreeChart) and the full styled item table with repeating
 * header. All labels localized via Accept-Language.
 */
@Component
@RequiredArgsConstructor
public class PdfReportRenderer {

    private static final Color BRAND_GREEN = new Color(0x2E, 0x7D, 0x32);
    private static final Color LIGHT_GREEN = new Color(0xE8, 0xF5, 0xE9);
    private static final Color ROW_SHADE = new Color(0xF5, 0xF5, 0xF5);
    // Brand green leads; the rest are deliberately distinct hues so adjacent
    // pie slices stay tellable-apart (an all-green ramp blurs together in print).
    private static final Color[] CHART_PALETTE = {
            new Color(0x2E, 0x7D, 0x32), new Color(0x26, 0xA6, 0x9A), new Color(0xFF, 0xB3, 0x00),
            new Color(0x6D, 0x4C, 0x41), new Color(0x66, 0xBB, 0x6A), new Color(0x54, 0x6E, 0x7A),
            new Color(0xEF, 0x6C, 0x00), new Color(0x00, 0x83, 0x8F), new Color(0x9E, 0x9D, 0x24),
            new Color(0x8D, 0x6E, 0x63)};
    private static final DateTimeFormatter CELL_DATE = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MM/yyyy");

    private final LocalizedMessageService localizedMessageService;

    public byte[] render(PurchaseReportData report) {
        try (var output = new ByteArrayOutputStream()) {
            var document = new Document(PageSize.A4, 36, 36, 48, 42);
            var writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new FooterOnEveryPage(translate("report.footer")));
            document.open();
            titleBand(document);
            kpiCards(document, report);
            charts(document, report);
            itemsTable(document, report);
            document.close();
            return output.toByteArray();
        } catch (IOException | DocumentException ex) {
            throw new UncheckedIOException("Failed to build PDF report", new IOException(ex));
        }
    }

    // ---------- sections ----------

    private void titleBand(Document document) {
        var title = new Paragraph(translate("report.title"),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, BRAND_GREEN));
        title.setSpacingAfter(4);
        document.add(title);
        var subtitle = new Paragraph(
                translate("report.generated-at", LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY));
        subtitle.setSpacingAfter(14);
        document.add(subtitle);
    }

    private void kpiCards(Document document, PurchaseReportData report) {
        var cards = new PdfPTable(4);
        cards.setWidthPercentage(100);
        cards.setSpacingAfter(16);
        addKpiCard(cards, translate("report.kpi.total-spent"), money(report.kpis().totalSpent()));
        addKpiCard(cards, translate("report.kpi.receipts"), String.valueOf(report.kpis().receiptCount()));
        addKpiCard(cards, translate("report.kpi.items"), String.valueOf(report.kpis().itemCount()));
        addKpiCard(cards, translate("report.kpi.average-ticket"), money(report.kpis().averageTicket()));
        document.add(cards);
    }

    private void addKpiCard(PdfPTable cards, String label, String value) {
        var cell = new PdfPCell();
        cell.setBackgroundColor(LIGHT_GREEN);
        cell.setBorderColor(Color.WHITE);
        cell.setPadding(8);
        cell.addElement(new Paragraph(label, FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY)));
        cell.addElement(new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND_GREEN)));
        cards.addCell(cell);
    }

    private void charts(Document document, PurchaseReportData report) throws DocumentException, IOException {
        if (!report.monthlySeries().isEmpty()) {
            document.add(chartImage(monthlyChart(report), 520, 180));
        }
        var half = new PdfPTable(2);
        half.setWidthPercentage(100);
        half.setSpacingBefore(8);
        half.setSpacingAfter(12);
        var hasBreakdown = !report.categoryBreakdown().isEmpty();
        var hasMarkets = !report.topMarkets().isEmpty();
        if (hasBreakdown) half.addCell(imageCell(chartImage(categoryChart(report), 255, 200)));
        if (hasMarkets) half.addCell(imageCell(chartImage(marketsChart(report), 255, 200)));
        if (hasBreakdown ^ hasMarkets) half.addCell(emptyCell());
        if (hasBreakdown || hasMarkets) document.add(half);
    }

    private void itemsTable(Document document, PurchaseReportData report) {
        var heading = new Paragraph(translate("report.sheet.items"),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_GREEN));
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(6);
        document.add(heading);

        var table = new PdfPTable(new float[]{7f, 16f, 20f, 6f, 5f, 8f, 8f, 12f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (var header : List.of(
                translate("export.header.date"), translate("export.header.market"),
                translate("export.header.item"), translate("export.header.quantity-short"),
                translate("export.header.unit-short"), translate("export.header.unit-price"),
                translate("export.header.item-total"), translate("export.header.category"))) {
            var cell = new PdfPCell(new Phrase(header, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE)));
            cell.setBackgroundColor(BRAND_GREEN);
            cell.setPadding(5);
            table.addCell(cell);
        }
        var shaded = false;
        for (var item : report.items()) {
            var background = shaded ? ROW_SHADE : Color.WHITE;
            table.addCell(bodyCell(item.issuedAt() == null ? "" : CELL_DATE.format(item.issuedAt()), background, Element.ALIGN_LEFT));
            table.addCell(bodyCell(safe(item.market()), background, Element.ALIGN_LEFT));
            table.addCell(bodyCell(safe(item.item()), background, Element.ALIGN_LEFT));
            table.addCell(bodyCell(quantityText(item.quantity()), background, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(safe(item.unit()), background, Element.ALIGN_CENTER));
            table.addCell(bodyCell(moneyText(item.unitPrice()), background, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(moneyText(item.itemTotal()), background, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(categoryLabel(item.category()), background, Element.ALIGN_LEFT));
            shaded = !shaded;
        }
        document.add(table);
    }

    /** Footer drawn at a fixed position on every page — never flows into an extra page. */
    private static final class FooterOnEveryPage extends PdfPageEventHelper {
        private final Phrase footerPhrase;

        private FooterOnEveryPage(String footerText) {
            this.footerPhrase = new Phrase(footerText,
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7.5f, Color.GRAY));
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                    footerPhrase, (document.left() + document.right()) / 2, document.bottom() - 22, 0);
        }
    }

    // ---------- charts (JFreeChart → PNG) ----------

    private JFreeChart monthlyChart(PurchaseReportData report) {
        var dataset = new DefaultCategoryDataset();
        for (var monthly : report.monthlySeries()) {
            dataset.addValue(monthly.total(), translate("report.chart.total"), MONTH_LABEL.format(monthly.month()));
        }
        // A line needs two points — a single month renders an empty plot, so
        // fall back to a bar for short histories and show markers otherwise.
        if (report.monthlySeries().size() < 2) {
            var chart = ChartFactory.createBarChart(translate("report.chart.monthly-title"),
                    null, null, dataset);
            styleChart(chart);
            flattenBars(chart);
            chart.removeLegend();
            return chart;
        }
        var chart = ChartFactory.createLineChart(translate("report.chart.monthly-title"),
                null, null, dataset);
        styleChart(chart);
        var plot = chart.getCategoryPlot();
        if (plot.getRenderer() instanceof LineAndShapeRenderer lineRenderer) {
            lineRenderer.setDefaultShapesVisible(true);
        }
        plot.getRenderer().setSeriesPaint(0, BRAND_GREEN);
        plot.getRenderer().setSeriesStroke(0, new BasicStroke(2.4f));
        return chart;
    }

    /** Kill JFreeChart's default glossy gradient and cap the bar width. */
    private static void flattenBars(JFreeChart chart) {
        var plot = chart.getCategoryPlot();
        if (plot.getRenderer() instanceof BarRenderer barRenderer) {
            barRenderer.setBarPainter(new StandardBarPainter());
            barRenderer.setMaximumBarWidth(0.18);
            barRenderer.setShadowVisible(false);
            barRenderer.setSeriesPaint(0, BRAND_GREEN);
        }
    }

    private JFreeChart categoryChart(PurchaseReportData report) {
        var dataset = new DefaultPieDataset<String>();
        for (var category : report.categoryBreakdown()) {
            dataset.setValue(categoryLabel(category.category()), category.total());
        }
        var chart = ChartFactory.createPieChart(translate("report.chart.categories-title"), dataset, true, false, false);
        styleChart(chart);
        if (chart.getPlot() instanceof PiePlot<?> piePlot) {
            piePlot.setBackgroundPaint(Color.WHITE);
            piePlot.setOutlineVisible(false);
            piePlot.setLabelGenerator(null); // legend carries the labels; slices stay clean
            var sliceIndex = 0;
            for (var category : report.categoryBreakdown()) {
                @SuppressWarnings("unchecked")
                var typedPlot = (PiePlot<String>) piePlot;
                typedPlot.setSectionPaint(categoryLabel(category.category()),
                        CHART_PALETTE[sliceIndex++ % CHART_PALETTE.length]);
            }
        }
        return chart;
    }

    private JFreeChart marketsChart(PurchaseReportData report) {
        var dataset = new DefaultCategoryDataset();
        for (var market : report.topMarkets()) {
            dataset.addValue(market.total(), translate("report.chart.total"), shortName(market.marketName()));
        }
        var chart = ChartFactory.createBarChart(translate("report.chart.markets-title"),
                null, null, dataset);
        styleChart(chart);
        flattenBars(chart);
        chart.removeLegend();
        var domainAxis = chart.getCategoryPlot().getDomainAxis();
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);
        return chart;
    }

    private static void styleChart(JFreeChart chart) {
        chart.setBackgroundPaint(Color.WHITE);
        chart.getTitle().setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12)); // awt Font — lowagie fonts come via FontFactory
        chart.getTitle().setPaint(BRAND_GREEN.darker());
        if (chart.getPlot() != null) {
            chart.getPlot().setBackgroundPaint(Color.WHITE);
            chart.getPlot().setOutlineVisible(false);
        }
    }

    private static Image chartImage(JFreeChart chart, int width, int height) throws IOException, DocumentException {
        var buffered = chart.createBufferedImage(width * 2, height * 2); // 2x for print sharpness
        var png = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", png);
        var image = Image.getInstance(png.toByteArray());
        image.scaleToFit(width, height);
        return image;
    }

    private static PdfPCell imageCell(Image image) {
        var cell = new PdfPCell(image, false);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell emptyCell() {
        var cell = new PdfPCell(new Phrase(""));
        cell.setBorder(PdfPCell.NO_BORDER);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Color background, int alignment) {
        var cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 7.5f)));
        cell.setBackgroundColor(background);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        cell.setBorderColor(Color.LIGHT_GRAY);
        return cell;
    }

    // ---------- formatting ----------

    private String categoryLabel(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return translate("category.OTHER");
        return translate("category." + categoryName);
    }

    private static String shortName(String marketName) {
        if (marketName == null) return "-";
        return marketName.length() <= 18 ? marketName : marketName.substring(0, 18);
    }

    private String money(BigDecimal value) {
        return "R$ " + (value == null ? "0,00"
                : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
    }

    private static String moneyText(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String quantityText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String translate(String key, Object... args) {
        return localizedMessageService.translate(key, args);
    }
}
