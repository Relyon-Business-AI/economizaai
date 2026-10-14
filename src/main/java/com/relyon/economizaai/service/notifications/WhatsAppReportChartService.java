package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.RingPlot;
import org.jfree.chart.title.LegendTitle;
import org.jfree.data.general.DefaultPieDataset;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders a household's last-7-days category spending as a donut-chart PNG for WhatsApp.
 * The biggest-spend slice is red (eye-catching, as requested), the rest cycle a vibrant
 * palette; the long tail is lumped into "Outros" so tiny slices don't clutter the ring.
 * Returns {@code null} when there's nothing to chart.
 *
 * <p>Sent as an image + caption in ONE WhatsApp message inside the free service window —
 * no extra money cost over the text-only reply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppReportChartService {

    private static final int TOP_SLICES = 6;

    private static final Color BRAND_GREEN = new Color(0x2E, 0x7D, 0x32);
    private static final Color BIGGEST_RED = new Color(0xE5, 0x39, 0x35); // maior gasto
    private static final Color OTHERS_GRAY = new Color(0x9E, 0x9E, 0x9E); // "Outros"
    // Vibrant, all dark enough for white labels. Used for the non-biggest, non-"Outros" slices.
    private static final Color[] PALETTE = {
            new Color(0xFB, 0x8C, 0x00), // orange
            new Color(0x00, 0x89, 0x7B), // teal
            new Color(0x39, 0x49, 0xAB), // indigo
            new Color(0x8E, 0x24, 0xAA), // purple
            new Color(0x43, 0xA0, 0x47), // green
            new Color(0x6D, 0x4C, 0x41), // brown
            new Color(0xC2, 0x18, 0x5B), // pink
            new Color(0x00, 0x83, 0x8F)};

    private final LocalizedMessageService messageService;

    /** PNG bytes of the category donut, or {@code null} when there's no category data. */
    public byte[] renderCategoryPie(PurchaseReportData report, Locale locale) {
        var breakdown = report.categoryBreakdown();
        if (breakdown == null || breakdown.isEmpty()) {
            return null;
        }

        var sorted = breakdown.stream()
                .filter(category -> category.total() != null && category.total().signum() > 0)
                .sorted(Comparator.comparing(CategorySpend::total).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return null;
        }

        var dataset = new DefaultPieDataset<String>();
        var orderedLabels = new ArrayList<String>();
        for (var category : sorted.stream().limit(TOP_SLICES).toList()) {
            var label = categoryLabel(category.category(), locale);
            dataset.setValue(label, category.total());
            orderedLabels.add(label);
        }
        var othersLabel = buildOthers(sorted, dataset, locale);

        var chart = ChartFactory.createRingChart(
                messageService.translate("whatsapp.report.chart-title", locale), dataset, true, false, false);
        styleChart(chart, locale);
        paintSlices(chart, orderedLabels, othersLabel);

        try {
            var image = chart.createBufferedImage(960, 760);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        } catch (Exception ex) {
            log.warn("whatsapp.report.chart_render_failed reason={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Lumps everything past the top slices into one "Outros" wedge. Returns its label, or null. */
    private String buildOthers(List<CategorySpend> sorted, DefaultPieDataset<String> dataset, Locale locale) {
        if (sorted.size() <= TOP_SLICES) {
            return null;
        }
        var othersTotal = sorted.stream().skip(TOP_SLICES)
                .map(CategorySpend::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (othersTotal.signum() <= 0) {
            return null;
        }
        var othersLabel = messageService.translate("whatsapp.report.chart-others", locale);
        dataset.setValue(othersLabel, othersTotal);
        return othersLabel;
    }

    private void styleChart(JFreeChart chart, Locale locale) {
        chart.setBackgroundPaint(Color.WHITE);
        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 30));
        chart.getTitle().setPaint(BRAND_GREEN.darker());

        var percentOnSlice = new StandardPieSectionLabelGenerator(
                "{2}", NumberFormat.getNumberInstance(locale), new DecimalFormat("0%"));
        var legendEntry = new StandardPieSectionLabelGenerator(
                "{0}  ({2})", NumberFormat.getNumberInstance(locale), new DecimalFormat("0%"));

        if (chart.getPlot() instanceof RingPlot plot) {
            plot.setBackgroundPaint(Color.WHITE);
            plot.setOutlineVisible(false);
            plot.setShadowPaint(null);
            plot.setSeparatorsVisible(true);
            plot.setSeparatorPaint(Color.WHITE);
            plot.setSectionDepth(0.42);
            plot.setSimpleLabels(true); // percentage centered in the ring band — no leader lines
            plot.setLabelGenerator(percentOnSlice);
            plot.setLabelFont(new Font("SansSerif", Font.BOLD, 20));
            plot.setLabelPaint(Color.WHITE);
            plot.setLabelBackgroundPaint(null);
            plot.setLabelOutlinePaint(null);
            plot.setLabelShadowPaint(null);
            plot.setLegendLabelGenerator(legendEntry);
        }

        for (Object title : chart.getSubtitles()) {
            if (title instanceof LegendTitle legend) {
                legend.setItemFont(new Font("SansSerif", Font.PLAIN, 22));
            }
        }
    }

    private void paintSlices(JFreeChart chart, List<String> orderedLabels, String othersLabel) {
        if (!(chart.getPlot() instanceof RingPlot plot)) {
            return;
        }
        for (var index = 0; index < orderedLabels.size(); index++) {
            plot.setSectionPaint(orderedLabels.get(index),
                    index == 0 ? BIGGEST_RED : PALETTE[(index - 1) % PALETTE.length]);
        }
        if (othersLabel != null) {
            plot.setSectionPaint(othersLabel, OTHERS_GRAY);
        }
    }

    private String categoryLabel(String categoryName, Locale locale) {
        if (categoryName == null || categoryName.isBlank()) {
            return messageService.translate("category.OTHER", locale);
        }
        return messageService.translate("category." + categoryName, locale);
    }
}
