package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ImportFileUnreadableException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFileTextExtractorTest {

    private static final String CHAVE = "43260915436940001177550010445204901195759810";

    private final ImportFileTextExtractor extractor = new ImportFileTextExtractor();

    @Test
    void plainTextPassesThroughAndYieldsChave() {
        var csv = "Munic.;Razao Social;Chave de Acesso\nNova Santa Rita;Amazon;" + CHAVE + "\n";
        var text = extractor.extractText(csv.getBytes(StandardCharsets.UTF_8));
        assertThat(ReceiptImportService.extractChaves(text)).containsExactly(CHAVE);
    }

    @Test
    void xlsxCellsAreExtractedIncludingTextChave() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Notas");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Razão Social");
            header.createCell(1).setCellValue("Chave de Acesso");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Amazon Servs De Var Do Brasil Ltda");
            row.createCell(1).setCellValue(CHAVE);
            workbook.write(output);
        }
        var text = extractor.extractText(output.toByteArray());
        assertThat(ReceiptImportService.extractChaves(text)).containsExactly(CHAVE);
    }

    @Test
    void pdfTextIsExtractedIncludingChave() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(40, 700);
                content.showText("Chave de Acesso: " + CHAVE);
                content.endText();
            }
            document.save(output);
        }
        var text = extractor.extractText(output.toByteArray());
        assertThat(ReceiptImportService.extractChaves(text)).containsExactly(CHAVE);
    }

    @Test
    void corruptSpreadsheetIsRejectedWithDomainException() {
        var corruptZip = new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> extractor.extractText(corruptZip))
                .isInstanceOf(ImportFileUnreadableException.class);
    }

    @Test
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> extractor.extractText(new byte[0]))
                .isInstanceOf(ImportFileUnreadableException.class);
    }
}
