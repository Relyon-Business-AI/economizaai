package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvrsSharedPortalAdapterTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
    private static final String CHAVE_SC = "42260412345678000190650010000123451123456780";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private final SvrsSharedPortalAdapter adapter = new SvrsSharedPortalAdapter(
            RestClient.builder(), NO_CAPTCHA, 5000, "test-agent", "RS", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");

    private String loadFixture() throws Exception {
        return loadFixture("nfce-sample.html");
    }

    private String loadFixture(String name) throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/rs/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void supportedStates_defaultsToRs() {
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.RS));
        assertEquals(1, adapter.supportedStates().size());
    }

    @Test
    void supportedStates_acceptsCsvOfMultipleUfs() {
        var multi = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA, 5000, "test", "RS, SC, RJ", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");
        assertEquals(3, multi.supportedStates().size());
        assertTrue(multi.supportedStates().contains(UnidadeFederativa.SC));
        assertTrue(multi.supportedStates().contains(UnidadeFederativa.RJ));
    }

    @Test
    void supportedStates_ignoresUnknownUfTokens() {
        var partial = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA, 5000, "test", "RS,XX,SC", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");
        assertEquals(2, partial.supportedStates().size());
        assertTrue(partial.supportedStates().contains(UnidadeFederativa.RS));
        assertTrue(partial.supportedStates().contains(UnidadeFederativa.SC));
    }

    @Test
    void supportedStates_blankConfigFallsBackToRs() {
        var fallback = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA, 5000, "test", "", 5, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br");
        assertEquals(1, fallback.supportedStates().size());
        assertTrue(fallback.supportedStates().contains(UnidadeFederativa.RS));
    }

    @Test
    void resolveUrl_acceptsFullUrl() {
        var url = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        assertEquals(url, adapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_acceptsAllowlistedSubdomain() {
        var url = "https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=" + CHAVE_RS;
        assertEquals(url, adapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_acceptsSantaCatarinaSecurityVerifyUrl() {
        var scAdapter = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA,
                5000, "test", "SC", 5, 0L, "sef.sc.gov.br");
        var url = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=encrypted-token";
        assertEquals(url, scAdapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_rejectsHostOutsideAllowlist() {
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://evil.example.com/?p=" + CHAVE_RS));
    }

    @Test
    void resolveUrl_rejectsInternalHosts() {
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("http://localhost:5432/"));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void resolveUrl_acceptsRealQrUrlWithRawPipeSeparators() {
        // real NFC-e QR payloads carry raw '|' in the query string — java.net.URI
        // rejects them, so host validation must not depend on URI parsing
        var url = "https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=" + CHAVE_RS + "|2|1|1|ABCDEF";
        assertEquals(url, adapter.resolveUrl(url));
    }

    @Test
    void resolveUrl_rejectsUserinfoBypassTrick() {
        // "allowed-host@evil.com" must not pass: the URL fetch would treat the
        // allowed host as userinfo and actually connect to evil.com
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://svrs.rs.gov.br@evil.com/?p=" + CHAVE_RS));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://svrs.rs.gov.br\\@evil.com/?p=" + CHAVE_RS));
    }

    @Test
    void resolveUrl_rejectsAllowlistedHostAsPrefixTrick() {
        // host must END with the allowed suffix — "svrs.rs.gov.br.evil.com" must not pass
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://svrs.rs.gov.br.evil.com/?p=" + CHAVE_RS));
    }

    @Test
    void resolveUrl_rejectsNonHttpSchemes() {
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("httpfile://svrs.rs.gov.br/etc/passwd"));
    }

    @Test
    void resolveUrl_buildsUrlFromParam() {
        var resolved = adapter.resolveUrl(CHAVE_RS + "|2|1|1|hash");
        assertTrue(resolved.startsWith("https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p="));
        assertTrue(resolved.contains(CHAVE_RS));
    }

    @Test
    void parseHtml_extractsItemsAndMetadata() throws Exception {
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, "https://example/test");

        assertEquals(CHAVE_RS, parsed.chaveAcesso());
        assertEquals("12345678000190", parsed.cnpjEmitente());
        assertNotNull(parsed.marketName());
        assertTrue(parsed.marketName().contains("ZAFFARI"));
        assertEquals(new BigDecimal("99.48"), parsed.totalAmount());
        assertNotNull(parsed.issuedAt());
        assertEquals(2026, parsed.issuedAt().getYear());

        assertEquals(3, parsed.items().size());

        var arroz = parsed.items().get(0);
        assertEquals(1, arroz.lineNumber());
        assertTrue(arroz.rawDescription().contains("ARROZ"));
        assertEquals("7891234567890", arroz.ean());
        assertEquals(new BigDecimal("2"), arroz.quantity());
        assertEquals("UN", arroz.unit());
        assertEquals(new BigDecimal("28.90"), arroz.unitPrice());
        assertEquals(new BigDecimal("57.80"), arroz.totalPrice());

        var banana = parsed.items().get(2);
        assertEquals(new BigDecimal("1.250"), banana.quantity());
        assertEquals("KG", banana.unit());
        assertEquals(new BigDecimal("8.74"), banana.totalPrice());
    }

    @Test
    void parseHtml_throwsWhenNoItems() {
        var html = "<html><body><div>no items here</div></body></html>";
        assertThrows(ReceiptParseException.class, () -> adapter.parseHtml(html, CHAVE_RS, null));
    }

    @Test
    void parseHtml_tracksReceiptDiscountWithoutTouchingItemPrices() throws Exception {
        var html = loadFixture("nfce-with-discount.html");
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);

        assertEquals(new BigDecimal("76.00"), parsed.totalAmount());
        // the "Descontos R$ 4,00" line is tracked, not distributed across items
        assertEquals(new BigDecimal("4.00"), parsed.discountTotal());
        assertEquals(2, parsed.items().size());

        // item prices stay gross-as-printed
        var arroz = parsed.items().get(0);
        assertEquals(new BigDecimal("50.00"), arroz.totalPrice());
        assertEquals(new BigDecimal("25.00"), arroz.unitPrice());

        var leite = parsed.items().get(1);
        assertEquals(new BigDecimal("30.00"), leite.totalPrice());
        assertEquals(new BigDecimal("30.00"), leite.unitPrice());

        // gross item sum == amount paid + tracked discount
        var sum = parsed.items().stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("80.00"), sum);
        assertEquals(0, sum.compareTo(parsed.totalAmount().add(parsed.discountTotal())));
    }

    @Test
    void parseHtml_extractsIbptApproxTaxWhenPresent() throws Exception {
        // Zaffari fixture carries: "Trib aprox R$ 51,73 Federal, R$ 49,35 Estadual Fonte: IBPT"
        var html = loadFixture("nfce-real-zaffari.html");
        var parsed = adapter.parseHtml(html, "43260493015006005182651130003394021410514546", null);
        assertEquals(0, parsed.approxTaxFederal().compareTo(new BigDecimal("51.73")));
        assertEquals(0, parsed.approxTaxEstadual().compareTo(new BigDecimal("49.35")));
    }

    @Test
    void parseHtml_returnsNullTaxWhenIbptLineMissing() throws Exception {
        // Synthetic sample fixture has no IBPT line — both fields must be null
        // so aggregations can filter receipts that didn't declare taxes.
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);
        assertEquals(null, parsed.approxTaxFederal());
        assertEquals(null, parsed.approxTaxEstadual());
    }

    @Test
    void parseHtml_stripsCpfBeforeStorage() throws Exception {
        var html = CpfMasker.strip(loadFixture());
        var parsed = adapter.parseHtml(html, CHAVE_RS, null);
        assertTrue(parsed.rawHtml().contains("***.***.***-**"));
        assertTrue(!parsed.rawHtml().contains("123.456.789-00"));
    }

    /** Builds an adapter whose HTTP layer is driven by {@code behavior(callNumber)}. */
    private SvrsSharedPortalAdapter adapterWithHttp(int maxAttempts, AtomicInteger calls,
                                                    IntFunction<String> behavior) {
        return new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA, 5000, "test", "RS", maxAttempts, 0L, "svrs.rs.gov.br,sefaz.rs.gov.br") {
            @Override
            protected String httpGet(String url) {
                return behavior.apply(calls.incrementAndGet());
            }
        };
    }

    @Test
    void fetchHtml_retriesTransientServerErrorsThenSucceeds() {
        var calls = new AtomicInteger();
        var stub = adapterWithHttp(5, calls, call -> {
            if (call < 3) throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            return "<html>ok</html>";
        });
        assertEquals("<html>ok</html>", stub.fetchHtml(CHAVE_RS));
        assertEquals(3, calls.get());
    }

    @Test
    void fetchHtml_retriesNetworkErrorsThenSucceeds() {
        var calls = new AtomicInteger();
        var stub = adapterWithHttp(5, calls, call -> {
            if (call == 1) throw new ResourceAccessException("read timed out", new IOException("timeout"));
            return "<html>ok</html>";
        });
        assertEquals("<html>ok</html>", stub.fetchHtml(CHAVE_RS));
        assertEquals(2, calls.get());
    }

    @Test
    void fetchHtml_retriesEmptyBodyThenSucceeds() {
        var calls = new AtomicInteger();
        var stub = adapterWithHttp(5, calls, call -> call == 1 ? "" : "<html>ok</html>");
        assertEquals("<html>ok</html>", stub.fetchHtml(CHAVE_RS));
        assertEquals(2, calls.get());
    }

    @Test
    void fetchHtml_scSecurityVerifyUrlThrowsCaptchaUnavailableBeforeChaveExtraction() {
        var scAdapter = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA,
                5000, "test", "SC", 5, 0L, "sef.sc.gov.br");
        var url = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=encrypted-token";

        assertThrows(CaptchaUnavailableException.class, () -> scAdapter.fetchHtml(url));
    }

    @Test
    void fetchHtml_scSecurityVerifyHtmlThrowsCaptchaUnavailable() {
        var calls = new AtomicInteger();
        var stub = new SvrsSharedPortalAdapter(RestClient.builder(), NO_CAPTCHA,
                5000, "test", "SC", 5, 0L, "svrs.rs.gov.br") {
            @Override
            protected String httpGet(String url) {
                calls.incrementAndGet();
                return """
                        <html>
                          <body>
                            <form action="/tax.NET/SecurityVerify.aspx">
                              <div>Validação de segurança</div>
                              <div>Cloudflare</div>
                              <button>Validar</button>
                            </form>
                          </body>
                        </html>
                        """;
            }
        };

        assertThrows(CaptchaUnavailableException.class, () -> stub.fetchHtml(CHAVE_SC));
        assertEquals(1, calls.get());
    }

    @Test
    void fetchHtml_exhaustsAllAttemptsThenThrows() {
        var calls = new AtomicInteger();
        var stub = adapterWithHttp(5, calls, call -> {
            throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        });
        assertThrows(SefazFetchException.class, () -> stub.fetchHtml(CHAVE_RS));
        assertEquals(5, calls.get());
    }

    @Test
    void fetchHtml_doesNotRetryClientErrors() {
        var calls = new AtomicInteger();
        var stub = adapterWithHttp(5, calls, call -> {
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
        });
        assertThrows(SefazFetchException.class, () -> stub.fetchHtml(CHAVE_RS));
        assertEquals(1, calls.get());
    }
}
