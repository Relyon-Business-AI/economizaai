package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DF adapter over real fixtures captured 2026-10-05 from a live scan (S.A.
 * Atacadista de Alimentos, 50 items, R$235,21): the Turnstile captcha page and the
 * DANFE returned after solving it + replaying the form POST.
 */
class DfNfcePortalAdapterTest {

    private static final String QR_URL = "http://www.fazenda.df.gov.br/nfce/qrcode?p="
            + "53261007738069000328653050000125721251741713|2|1|1|B696762EBDC4918616072F47098358604E19034C";
    private static final String CHAVE = "53261007738069000328653050000125721251741713";

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/sefaz/df/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final CaptchaSolver TOKEN_SOLVER = new CaptchaSolver() {
        @Override public boolean isConfigured() { return true; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) { return "recaptcha"; }
        @Override public String solveCloudflareTurnstile(String siteKey, String pageUrl) { return "turnstile-token"; }
    };

    private static final CaptchaSolver NO_SOLVER = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) { return null; }
    };

    private static DfNfcePortalAdapter adapter(CaptchaSolver solver,
                                               Function<String, String> get,
                                               Function<String, String> post,
                                               AtomicReference<MultiValueMap<String, String>> capturedBody) {
        return new DfNfcePortalAdapter(RestClient.builder(), solver, 1000, 3, 0, 60000L, "test") {
            @Override
            protected PortalPage httpGet(String url) {
                return new PortalPage(get.apply(url), "ASP.NET_SessionId=abc123");
            }

            @Override
            protected String httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
                if (capturedBody != null) capturedBody.set(body);
                return post.apply(url);
            }
        };
    }

    @Test
    void fetchHtml_solvesTurnstileThenPostsForm_returnsDanfe() {
        var body = new AtomicReference<MultiValueMap<String, String>>();
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"),
                url -> fixture("marcosbko-atacadista.html"), body);

        var danfe = adapter.fetchHtml(QR_URL);

        assertTrue(danfe.contains("ATACADISTA"));
        // POST carried the chave + the solved token in the reCAPTCHA-compat field.
        assertEquals(CHAVE, body.get().getFirst("Chave"));
        assertEquals("turnstile-token", body.get().getFirst("g-recaptcha-response"));
    }

    @Test
    void fetchHtml_thenParse_yieldsRealDfReceipt() {
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"),
                url -> fixture("marcosbko-atacadista.html"), null);

        var html = adapter.fetchHtml(QR_URL);
        var parsed = adapter.parseHtml(html, CHAVE, QR_URL);

        assertEquals("07738069000328", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("ATACADISTA"), parsed.marketName());
        assertEquals(0, new BigDecimal("235.21").compareTo(parsed.totalAmount()));
        assertEquals(50, parsed.items().size());
        assertEquals(LocalDateTime.of(2026, 10, 2, 12, 9, 10), parsed.issuedAt());

        var cheiroVerde = parsed.items().stream()
                .filter(item -> item.rawDescription().contains("CHEIRO VERDE")).findFirst().orElseThrow();
        assertNull(cheiroVerde.ean(), "DF reports a merchant PLU, not a GTIN");
        assertEquals(0, BigDecimal.ONE.compareTo(cheiroVerde.quantity()));
        assertEquals("UN", cheiroVerde.unit());
        assertEquals(0, new BigDecimal("3.49").compareTo(cheiroVerde.totalPrice()));
        assertTrue(parsed.items().stream().allMatch(item -> item.totalPrice().signum() >= 0));
    }

    @Test
    void fetchHtml_noSolver_throwsCaptchaUnavailable() {
        var adapter = adapter(NO_SOLVER, url -> fixture("challenge.html"),
                url -> fixture("marcosbko-atacadista.html"), null);

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(QR_URL));
    }

    @Test
    void fetchHtml_rejectedToken_retriesThenThrowsFetch() {
        // POST keeps returning the captcha page → token rejected each attempt.
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"),
                url -> fixture("challenge.html"), null);

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL));
    }

    @Test
    void fetchHtml_danfeServedWithoutChallenge_returnsDirectly() {
        var adapter = adapter(NO_SOLVER, url -> fixture("marcosbko-atacadista.html"), url -> "unused", null);

        assertTrue(adapter.fetchHtml(QR_URL).contains("ATACADISTA"));
    }

    @Test
    void supportedStates_isDf() {
        var adapter = adapter(TOKEN_SOLVER, url -> "", url -> "", null);

        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.DF));
    }
}
