package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real São Paulo NFC-e (Mercadao Atacadista, Guarujá, 2026-07-05) served by SP's
 * own portal ({@code www.nfce.fazenda.sp.gov.br}, ASP.NET ConsultaQRCode.aspx).
 * SP does NOT delegate to SVRS and gates nothing behind a captcha — a plain GET
 * of the QR URL returns the same responsive DANFE ({@code #tabResult} markup),
 * so the shared adapter parses it unchanged. Locks in:
 * <ul>
 *   <li>market name / CNPJ / address / issuedAt extraction on SP markup;</li>
 *   <li>all 20 items with merchant-internal item codes (NOT EANs — below the
 *       8-digit floor, so {@code ean} stays null and matching falls back to
 *       description);</li>
 *   <li>total from "Valor a pagar" (txtMax), not "Qtd. total de itens";</li>
 *   <li>the adapter claims UF SP and accepts the real SP QR host.</li>
 * </ul>
 */
class RealSaoPauloFixtureTest {

    private static final String CHAVE = "35260716881767001421650010000620781001241617";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS,PR,SP", 5, 0L,
            "svrs.rs.gov.br,sefaz.rs.gov.br,fazenda.pr.gov.br,fazenda.sp.gov.br");

    @Test
    void parseSaoPauloReceipt_extractsAllFields() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/sp/nfce-real-mercadao.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = adapter.parseHtml(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("16881767001421", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("MERCADAO ATACADISTA"));
        assertTrue(parsed.marketAddress().toUpperCase().contains("GUARUJA"));
        assertEquals(LocalDateTime.of(2026, 7, 5, 14, 11, 14), parsed.issuedAt());
        assertEquals(0, new BigDecimal("158.12").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal(), "no discount line on this receipt");

        assertEquals(20, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertTrue(first.rawDescription().contains("LARANJA PERA"));
        assertNull(first.ean(), "merchant-internal code (517) is not an EAN");
        assertEquals(0, new BigDecimal("0.94").compareTo(first.quantity()));
        assertEquals("KG", first.unit());
        assertEquals(0, new BigDecimal("2.98").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("2.80").compareTo(first.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }

    @Test
    void adapterClaimsSaoPaulo() {
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.SP));
    }

    @Test
    void resolveUrl_acceptsTheRealSaoPauloQrUrl() {
        var url = "https://www.nfce.fazenda.sp.gov.br/NFCeConsultaPublica/Paginas/"
                + "ConsultaQRCode.aspx?p=" + CHAVE + "|2|1|1|cf81b5a299473d34142295c4b81b6d33ea5de63a";
        assertEquals(url, adapter.resolveUrl(url));
    }
}
