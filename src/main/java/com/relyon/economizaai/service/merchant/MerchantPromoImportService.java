package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.MerchantPromoRequest;
import com.relyon.economizaai.dto.response.MerchantPromoImportResponse;
import com.relyon.economizaai.dto.response.MerchantPromoImportResponse.RejectedRow;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.MerchantPromoImportException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulk promo ingestion — the way real markets publish promos: a price-table
 * export from the ERP. Three equivalent entry points, one row semantics:
 * CSV upload, XLSX upload (first sheet), and a JSON batch for direct ERP/API
 * integration. Per-row validation reuses MerchantPromoService.buildValidated;
 * bad rows are reported (line + localized reason) without failing the batch.
 *
 * <p>Expected columns (header names accent/case-insensitive, pt aliases):
 * {@code ean}, {@code preco|preco_promocional}, {@code preco_normal|preco_regular}
 * (optional), {@code inicio|data_inicio}, {@code fim|data_fim},
 * {@code descricao} (optional). Dates accept {@code yyyy-MM-dd} and
 * {@code dd/MM/yyyy}; prices accept "4,99", "4.99" and "R$ 4,99".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantPromoImportService {

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("ean", "ean"),
            Map.entry("codigo", "ean"),
            Map.entry("codigo_barras", "ean"),
            Map.entry("gtin", "ean"),
            Map.entry("preco", "promoPrice"),
            Map.entry("preco_promocional", "promoPrice"),
            Map.entry("preco_promo", "promoPrice"),
            Map.entry("preco_oferta", "promoPrice"),
            Map.entry("preco_normal", "regularPrice"),
            Map.entry("preco_regular", "regularPrice"),
            Map.entry("preco_de", "regularPrice"),
            Map.entry("inicio", "startsAt"),
            Map.entry("data_inicio", "startsAt"),
            Map.entry("fim", "endsAt"),
            Map.entry("data_fim", "endsAt"),
            Map.entry("validade", "endsAt"),
            Map.entry("descricao", "description"),
            Map.entry("produto", "description"));

    private final MerchantPromoService merchantPromoService;
    private final MerchantPromoRepository promoRepository;
    private final MerchantSubscriptionService merchantSubscriptionService;
    private final CollaborativeProperties properties;
    private final LocalizedMessageService localizedMessageService;

    @Transactional
    public MerchantPromoImportResponse importFile(User merchantUser, String requestedCnpjRoot,
                                                  MultipartFile file) {
        var cnpjRoot = merchantPromoService.resolveChain(merchantUser, requestedCnpjRoot);
        merchantSubscriptionService.requirePublishing(cnpjRoot);
        var filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        var source = filename.endsWith(".xlsx") ? MerchantPromoSource.XLSX : MerchantPromoSource.CSV;
        var rows = source == MerchantPromoSource.XLSX ? parseXlsx(file) : parseCsv(file);
        return importRows(merchantUser, cnpjRoot, rows, source);
    }

    @Transactional
    public MerchantPromoImportResponse importBatch(User merchantUser, String requestedCnpjRoot,
                                                   List<MerchantPromoRequest> promos) {
        var cnpjRoot = merchantPromoService.resolveChain(merchantUser, requestedCnpjRoot);
        merchantSubscriptionService.requirePublishing(cnpjRoot);
        var rows = new ArrayList<ParsedRow>();
        for (var index = 0; index < promos.size(); index++) {
            rows.add(new ParsedRow(index + 1, promos.get(index), null));
        }
        return importRows(merchantUser, cnpjRoot, rows, MerchantPromoSource.API);
    }

    private MerchantPromoImportResponse importRows(User merchantUser, String cnpjRoot,
                                                   List<ParsedRow> rows, MerchantPromoSource source) {
        var maxRows = properties.getMerchant().getImportMaxRows();
        var capped = rows.size() > maxRows ? rows.subList(0, maxRows) : rows;
        if (rows.size() > maxRows) {
            log.warn("merchant.promo.import.capped cnpjRoot={} received={} cap={}", cnpjRoot, rows.size(), maxRows);
        }
        var errors = new ArrayList<RejectedRow>();
        var imported = 0;
        for (var row : capped) {
            if (row.parseErrorKey() != null) {
                errors.add(rejectedRow(row.line(), row.request(), row.parseErrorKey()));
                continue;
            }
            try {
                var promo = merchantPromoService.buildValidated(merchantUser, cnpjRoot, row.request(), source, null);
                promoRepository.save(promo);
                imported++;
            } catch (DomainException ex) {
                errors.add(rejectedRow(row.line(), row.request(), ex.getMessageKey()));
            }
        }
        log.info("merchant.promo.import.done cnpjRoot={} source={} received={} imported={} rejected={}",
                cnpjRoot, source, rows.size(), imported, errors.size());
        return new MerchantPromoImportResponse(rows.size(), imported, errors.size(), errors);
    }

    private RejectedRow rejectedRow(int line, MerchantPromoRequest request, String reasonKey) {
        var ean = request == null ? null : request.ean();
        return new RejectedRow(line, ean, reasonKey, localizedMessageService.translate(reasonKey));
    }

    // ---------- CSV ----------

    private List<ParsedRow> parseCsv(MultipartFile file) {
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new MerchantPromoImportException();
        }
        var lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) {
            throw new MerchantPromoImportException();
        }
        var delimiter = detectDelimiter(lines[0]);
        var columns = mapHeader(lines[0].split(delimiter, -1));
        if (!columns.containsKey("ean") || !columns.containsKey("promoPrice")) {
            throw new MerchantPromoImportException();
        }
        var rows = new ArrayList<ParsedRow>();
        for (var lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            if (lines[lineIndex].isBlank()) continue;
            var cells = lines[lineIndex].split(delimiter, -1);
            rows.add(buildRow(lineIndex + 1, columns, columnIndex ->
                    columnIndex < cells.length ? cells[columnIndex].trim() : ""));
        }
        return rows;
    }

    private static String detectDelimiter(String headerLine) {
        if (headerLine.contains(";")) return ";";
        if (headerLine.contains("\t")) return "\t";
        return ",";
    }

    // ---------- XLSX ----------

    private List<ParsedRow> parseXlsx(MultipartFile file) {
        var formatter = new DataFormatter(new Locale("pt", "BR"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getBytes()))) {
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new MerchantPromoImportException();
            }
            var headerCells = new ArrayList<String>();
            headerRow.forEach(cell -> headerCells.add(formatter.formatCellValue(cell)));
            var columns = mapHeader(headerCells.toArray(new String[0]));
            if (!columns.containsKey("ean") || !columns.containsKey("promoPrice")) {
                throw new MerchantPromoImportException();
            }
            var rows = new ArrayList<ParsedRow>();
            for (var rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var sheetRow = sheet.getRow(rowIndex);
                if (sheetRow == null) continue;
                var line = rowIndex + 1;
                rows.add(buildRow(line, columns, columnIndex -> cellText(sheetRow, columnIndex, formatter)));
            }
            return rows;
        } catch (MerchantPromoImportException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("merchant.promo.import.xlsx_unreadable {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new MerchantPromoImportException();
        }
    }

    /** Dates typed as real Excel dates come back ISO; everything else via the pt-BR formatter. */
    private static String cellText(Row sheetRow, int columnIndex, DataFormatter formatter) {
        var cell = sheetRow.getCell(columnIndex);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    // ---------- shared row building ----------

    /** header name → logical field, accents/case/whitespace-insensitive. */
    private static Map<String, Integer> mapHeader(String[] headerCells) {
        var columns = new HashMap<String, Integer>();
        for (var index = 0; index < headerCells.length; index++) {
            var normalized = normalizeHeader(headerCells[index]);
            var field = HEADER_ALIASES.get(normalized);
            if (field != null) {
                columns.putIfAbsent(field, index);
            }
        }
        return columns;
    }

    private static String normalizeHeader(String header) {
        var stripped = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    private interface CellReader {
        String read(int columnIndex);
    }

    private ParsedRow buildRow(int line, Map<String, Integer> columns, CellReader cells) {
        var ean = digitsOnly(valueOf(columns, cells, "ean"));
        var promoPrice = parsePrice(valueOf(columns, cells, "promoPrice"));
        if (promoPrice == null) {
            return new ParsedRow(line, requestOf(ean, null, null, null, null, null), "merchant.promo.invalid.price");
        }
        var regularPrice = parsePrice(valueOf(columns, cells, "regularPrice"));
        var startsAt = parseDate(valueOf(columns, cells, "startsAt"));
        var endsAt = parseDate(valueOf(columns, cells, "endsAt"));
        if (startsAt == null || endsAt == null) {
            return new ParsedRow(line, requestOf(ean, null, promoPrice, regularPrice, null, null),
                    "merchant.promo.invalid.dates");
        }
        var description = valueOf(columns, cells, "description");
        return new ParsedRow(line,
                requestOf(ean, description.isBlank() ? null : description, promoPrice, regularPrice, startsAt, endsAt),
                null);
    }

    private static MerchantPromoRequest requestOf(String ean, String description, BigDecimal promoPrice,
                                                  BigDecimal regularPrice, LocalDate startsAt, LocalDate endsAt) {
        return new MerchantPromoRequest(null, ean, description, promoPrice, regularPrice, startsAt, endsAt);
    }

    private static String valueOf(Map<String, Integer> columns, CellReader cells, String field) {
        var columnIndex = columns.get(field);
        return columnIndex == null ? "" : cells.read(columnIndex);
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    /** "R$ 4,99" / "4,99" / "4.99" → 4.99. Null when absent/unparseable. */
    static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var cleaned = raw.replace("R$", "").replace(" ", "").trim();
        if (cleaned.contains(",")) {
            cleaned = cleaned.replace(".", "").replace(',', '.');
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** yyyy-MM-dd or dd/MM/yyyy. Null when absent/unparseable. */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var trimmed = raw.trim();
        try {
            return trimmed.contains("/") ? LocalDate.parse(trimmed, BR_DATE) : LocalDate.parse(trimmed);
        } catch (Exception ex) {
            return null;
        }
    }

    private record ParsedRow(int line, MerchantPromoRequest request, String parseErrorKey) {}
}
