package com.relyon.economizaai.service;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.service.report.PurchaseReportData;
import com.relyon.economizaai.service.report.PurchaseReportData.ItemRow;
import com.relyon.economizaai.service.report.PdfReportRenderer;
import com.relyon.economizaai.service.report.XlsxReportRenderer;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Export/report of the household's confirmed purchase history (user
 * suggestion, 2026-07-21) in three shapes over one shared data assembly:
 * flat CSV (Brazilian-Excel-friendly), a charted multi-sheet XLSX and a
 * styled PDF. The chave de acesso rides along in every format so the note
 * can be looked up externally.
 *
 * <p>PRO-gated per MONETIZATION.md; while subscription enforcement is off
 * (current default) every tier can use it. The FREE history window still
 * applies via {@code clampFrom}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptExportService {

    public enum ExportFormat { CSV, XLSX, PDF }

    // A transient file carrier that's never compared for equality; content-based
    // equals/hashCode on the byte[] would be needless overhead. Reference identity is fine.
    @SuppressWarnings("java:S6218")
    public record ExportFile(byte[] content, String mediaType, String fileExtension) {}

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String SEPARATOR = ";";
    private static final String UTF8_BOM = "\uFEFF";
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADER_KEYS = List.of(
            "export.header.date", "export.header.market", "export.header.market-cnpj",
            "export.header.chave-acesso", "export.header.item", "export.header.quantity",
            "export.header.unit", "export.header.unit-price", "export.header.item-total",
            "export.header.category", "export.header.receipt-total");

    private final PurchaseReportAssembler reportAssembler;
    private final XlsxReportRenderer xlsxReportRenderer;
    private final PdfReportRenderer pdfReportRenderer;
    private final SubscriptionGateService subscriptionGate;
    private final LocalizedMessageService localizedMessageService;

    @Transactional(readOnly = true)
    public ExportFile exportPurchaseHistory(User user, LocalDateTime from, LocalDateTime to,
                                            ExportFormat format) {
        subscriptionGate.require(user, Feature.CSV_EXPORT);
        var effectiveFrom = subscriptionGate.clampFrom(user, from);
        var report = reportAssembler.assemble(user, effectiveFrom, to);
        var file = switch (format) {
            case XLSX -> new ExportFile(xlsxReportRenderer.render(report), XLSX_MEDIA_TYPE, "xlsx");
            case PDF -> new ExportFile(pdfReportRenderer.render(report), "application/pdf", "pdf");
            case CSV -> writeCsv(report);
        };
        log.info("export.done format={} rows={} from={} to={}",
                format, report.items().size(), effectiveFrom, to);
        return file;
    }

    // ---------- CSV (flat, pt-BR Excel-friendly) ----------

    private ExportFile writeCsv(PurchaseReportData report) {
        var csv = new StringBuilder(UTF8_BOM).append(headerLine());
        for (var row : report.items()) {
            csv.append('\n').append(csvLine(row));
        }
        return new ExportFile(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", "csv");
    }

    private String headerLine() {
        return HEADER_KEYS.stream()
                .map(localizedMessageService::translate)
                .map(ReceiptExportService::escape)
                .collect(Collectors.joining(SEPARATOR));
    }

    private static String csvLine(ItemRow row) {
        return List.of(
                        row.issuedAt() != null ? CSV_DATE.format(row.issuedAt()) : "",
                        nullToEmpty(row.market()),
                        nullToEmpty(row.marketCnpj()),
                        nullToEmpty(row.chaveAcesso()),
                        nullToEmpty(row.item()),
                        number(row.quantity()),
                        nullToEmpty(row.unit()),
                        number(row.unitPrice()),
                        number(row.itemTotal()),
                        row.category(),
                        number(row.receiptTotal()))
                .stream()
                .map(ReceiptExportService::escape)
                .collect(Collectors.joining(SEPARATOR));
    }

    /** Comma decimals (pt-BR Excel), plain string otherwise empty. */
    private static String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString().replace('.', ',');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * RFC-4180-style quoting, adapted to the semicolon separator. Cells that
     * would be interpreted as formulas get a leading apostrophe — item
     * descriptions come from receipt text an attacker can influence, and this
     * file is built to be opened in Excel (=WEBSERVICE/DDE injection).
     */
    private static String escape(String value) {
        var neutralized = neutralizeFormula(value);
        if (neutralized.contains(SEPARATOR) || neutralized.contains("\"")
                || neutralized.contains("\n") || neutralized.contains("\r")) {
            return '"' + neutralized.replace("\"", "\"\"") + '"';
        }
        return neutralized;
    }

    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) return value;
        var first = value.charAt(0);
        if (first == '=' || first == '+' || first == '@' || first == '\t'
                || (first == '-' && !isNegativeNumber(value))) {
            return "'" + value;
        }
        return value;
    }

    /** Keeps legitimate negative amounts ("-12,34") intact for Excel. */
    private static boolean isNegativeNumber(String value) {
        return value.matches("-\\d+(,\\d+)?");
    }
}
