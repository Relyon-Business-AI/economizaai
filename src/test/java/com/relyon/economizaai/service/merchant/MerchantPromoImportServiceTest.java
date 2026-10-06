package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.MerchantPromoRequest;
import com.relyon.economizaai.exception.MerchantPromoImportException;
import com.relyon.economizaai.exception.MerchantPromoInvalidException;
import com.relyon.economizaai.model.MerchantPromo;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantPromoSource;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MerchantPromoRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantPromoImportServiceTest {

    private static final String CHAIN_ROOT = "93015006";

    @Mock private MerchantPromoService merchantPromoService;
    @Mock private MerchantPromoRepository promoRepository;
    @Mock private MerchantSubscriptionService merchantSubscriptionService;
    @Mock private LocalizedMessageService localizedMessageService;

    private User merchantUser;
    private MerchantPromoImportService service;

    @BeforeEach
    void setUp() {
        service = new MerchantPromoImportService(merchantPromoService, promoRepository,
                merchantSubscriptionService, new CollaborativeProperties(), localizedMessageService);
        merchantUser = User.builder().id(UUID.randomUUID()).name("Mercado Teste")
                .email("merchant@economizaai.app").role(Role.MERCHANT).build();
        lenient().when(merchantPromoService.resolveChain(eq(merchantUser), any())).thenReturn(CHAIN_ROOT);
        lenient().when(localizedMessageService.translate(anyString())).thenReturn("mensagem localizada");
        lenient().when(merchantPromoService.buildValidated(eq(merchantUser), eq(CHAIN_ROOT),
                        any(MerchantPromoRequest.class), any(), any()))
                .thenAnswer(invocation -> MerchantPromo.builder()
                        .cnpjRoot(CHAIN_ROOT)
                        .ean(((MerchantPromoRequest) invocation.getArgument(2)).ean())
                        .promoPrice(((MerchantPromoRequest) invocation.getArgument(2)).promoPrice())
                        .startsAt(LocalDate.now()).endsAt(LocalDate.now().plusDays(7))
                        .source(MerchantPromoSource.CSV)
                        .build());
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "promos.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importCsv_brazilianFormats_importsAllRows() {
        var file = csv("""
                EAN;Preço Promocional;Preço Normal;Início;Fim;Descrição
                7891000100103;R$ 4,99;6,49;10/10/2026;17/10/2026;Leite 1L
                7891000100110;3.50;;2026-10-10;2026-10-17;Arroz 1kg
                """);

        var report = service.importFile(merchantUser, null, file);

        assertThat(report.received()).isEqualTo(2);
        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void importCsv_badRows_reportedPerLineWithoutFailingBatch() {
        when(merchantPromoService.buildValidated(eq(merchantUser), eq(CHAIN_ROOT),
                argThatEan("123"), any(), any()))
                .thenThrow(new MerchantPromoInvalidException("merchant.promo.invalid.ean"));
        var file = csv("""
                ean;preco;inicio;fim
                7891000100103;4,99;10/10/2026;17/10/2026
                123;4,99;10/10/2026;17/10/2026
                7891000100110;preço-inválido;10/10/2026;17/10/2026
                """);

        var report = service.importFile(merchantUser, null, file);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.rejected()).isEqualTo(2);
        assertThat(report.errors()).extracting(error -> error.reasonKey())
                .containsExactlyInAnyOrder("merchant.promo.invalid.ean", "merchant.promo.invalid.price");
        assertThat(report.errors()).allSatisfy(error -> assertThat(error.reason()).isNotBlank());
    }

    private static MerchantPromoRequest argThatEan(String ean) {
        return argThat(request -> request != null && ean.equals(request.ean()));
    }

    @Test
    void importCsv_missingRequiredHeaders_rejected() {
        var file = csv("""
                nome;valor
                Leite;4,99
                """);

        assertThrows(MerchantPromoImportException.class,
                () -> service.importFile(merchantUser, null, file));
    }

    @Test
    void importXlsx_firstSheet_imports() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("promos");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("ean");
            header.createCell(1).setCellValue("preco");
            header.createCell(2).setCellValue("inicio");
            header.createCell(3).setCellValue("fim");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("7891000100103");
            row.createCell(1).setCellValue("4,99");
            row.createCell(2).setCellValue("10/10/2026");
            row.createCell(3).setCellValue("17/10/2026");
            workbook.write(output);
        }
        var file = new MockMultipartFile("file", "promos.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());

        var report = service.importFile(merchantUser, null, file);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void importBatch_jsonRows_sameSemantics() {
        var rows = List.of(new MerchantPromoRequest(null, "7891000100103", "Leite",
                new BigDecimal("4.99"), null, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 17)));

        var report = service.importBatch(merchantUser, null, rows);

        assertThat(report.imported()).isEqualTo(1);
    }

    @Test
    void parsePrice_acceptsBrazilianAndIsoFormats() {
        assertThat(MerchantPromoImportService.parsePrice("R$ 1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(MerchantPromoImportService.parsePrice("4.99")).isEqualByComparingTo("4.99");
        assertThat(MerchantPromoImportService.parsePrice("abc")).isNull();
    }

    @Test
    void parseDate_acceptsIsoAndBrazilianFormats() {
        assertThat(MerchantPromoImportService.parseDate("2026-10-10")).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(MerchantPromoImportService.parseDate("10/10/2026")).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(MerchantPromoImportService.parseDate("10-10")).isNull();
    }
}
