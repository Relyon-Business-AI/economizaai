package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.report.PurchaseReportData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.PiePlot;
import org.jfree.data.general.DefaultPieDataset;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Renders a household's last-7-days category spending as a pie-chart PNG for WhatsApp.
 * Reuses the brand palette from the PDF report. Returns {@code null} when there's nothing
 * to chart (no categories) so the caller falls back to a text-only reply.
 *
 * <p>Sent as an image + caption in ONE WhatsApp message inside the free service window —
 * no extra money cost over the text-only reply (same single message, Twilio per-message
 * fee is identical for media, Meta service conversation is free).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppReportChartService {

    private static final Color BRAND_GREEN = new Color(0x2E, 0x7D, 0x32);
    private static final Color[] PALETTE = {
            new Color(0x2E, 0x7D, 0x32), new Color(0x26, 0xA6, 0x9A), new Color(0xFF, 0xB3, 0x00),
            new Color(0x6D, 0x4C, 0x41), new Color(0x66, 0xBB, 0x6A), new Color(0x54, 0x6E, 0x7A),
            new Color(0xEF, 0x6C, 0x00), new Color(0x00, 0x83, 0x8F), new Color(0x9E, 0x9D, 0x24),
            new Color(0x8D, 0x6E, 0x63)};

    private final LocalizedMessageService messageService;

    /** PNG bytes of the category pie, or {@code null} when there's no category data. */
    public byte[] renderCategoryPie(PurchaseReportData report, Locale locale) {
        var breakdown = report.categoryBreakdown();
        if (breakdown == null || breakdown.isEmpty()) {
            return null;
        }

        var dataset = new DefaultPieDataset<String>();
        for (var category : breakdown) {
            dataset.setValue(categoryLabel(category.category(), locale), category.total());
        }

        var chart = ChartFactory.createPieChart(
                messageService.translate("whatsapp.report.chart-title", locale), dataset, true, false, false);
        chart.setBackgroundPaint(Color.WHITE);
        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 26));
        chart.getTitle().setPaint(BRAND_GREEN.darker());
        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(new Font("SansSerif", Font.PLAIN, 18));
        }

        if (chart.getPlot() instanceof PiePlot<?> piePlot) {
            piePlot.setBackgroundPaint(Color.WHITE);
            piePlot.setOutlineVisible(false);
            piePlot.setLabelFont(new Font("SansSerif", Font.BOLD, 16));
            piePlot.setLabelBackgroundPaint(new Color(255, 255, 255, 200));
            piePlot.setLabelGenerator(new StandardPieSectionLabelGenerator("{2}")); // percentage on each slice
            @SuppressWarnings("unchecked")
            var typedPlot = (PiePlot<String>) piePlot;
            var sliceIndex = 0;
            for (var category : breakdown) {
                typedPlot.setSectionPaint(categoryLabel(category.category(), locale),
                        PALETTE[sliceIndex++ % PALETTE.length]);
            }
        }

        try {
            var image = chart.createBufferedImage(900, 700);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        } catch (Exception ex) {
            log.warn("whatsapp.report.chart_render_failed reason={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private String categoryLabel(String categoryName, Locale locale) {
        if (categoryName == null || categoryName.isBlank()) {
            return messageService.translate("category.OTHER", locale);
        }
        return messageService.translate("category." + categoryName, locale);
    }
}
