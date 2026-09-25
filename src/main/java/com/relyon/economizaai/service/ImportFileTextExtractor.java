package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ImportFileUnreadableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns whatever the state-program portals export (CSV/TXT, Excel, PDF — the
 * classic DataTables button set) into plain text, so chave extraction stays
 * format-agnostic. Format is detected by magic bytes, never by filename.
 */
@Slf4j
@Component
public class ImportFileTextExtractor {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    public String extractText(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) throw new ImportFileUnreadableException();
        if (startsWith(fileBytes, PDF_MAGIC)) return extractFromPdf(fileBytes);
        if (startsWith(fileBytes, ZIP_MAGIC) || startsWith(fileBytes, OLE2_MAGIC)) {
            return extractFromSpreadsheet(fileBytes);
        }
        return new String(fileBytes, StandardCharsets.UTF_8);
    }

    private String extractFromPdf(byte[] fileBytes) {
        try (var document = Loader.loadPDF(fileBytes)) {
            var text = new PDFTextStripper().getText(document);
            log.debug("import.file pdf pages={} chars={}", document.getNumberOfPages(), text.length());
            return text;
        } catch (Exception ex) {
            log.warn("import.file.unreadable format=pdf error={}", ex.getMessage());
            throw new ImportFileUnreadableException();
        }
    }

    private String extractFromSpreadsheet(byte[] fileBytes) {
        var formatter = new DataFormatter();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            var text = new StringBuilder();
            for (var sheet : workbook) {
                for (var row : sheet) {
                    for (var cell : row) {
                        var value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) text.append(value).append('\t');
                    }
                    text.append('\n');
                }
            }
            log.debug("import.file spreadsheet sheets={} chars={}", workbook.getNumberOfSheets(), text.length());
            return text.toString();
        } catch (Exception ex) {
            log.warn("import.file.unreadable format=spreadsheet error={}", ex.getMessage());
            throw new ImportFileUnreadableException();
        }
    }

    private static boolean startsWith(byte[] fileBytes, byte[] magic) {
        if (fileBytes.length < magic.length) return false;
        for (var index = 0; index < magic.length; index++) {
            if (fileBytes[index] != magic[index]) return false;
        }
        return true;
    }
}
