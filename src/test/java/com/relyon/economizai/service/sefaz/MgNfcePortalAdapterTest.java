package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MG adapter over real fixtures captured 2026-09-21 from a live scan (Supermercado
 * Borges e Miranda, 6 items, R$73,45): the Turnstile challenge page and the DANFE
 * returned after solving it + replaying the JSF form.
 */
class MgNfcePortalAdapterTest {

    private static final String QR_URL = "https://portalsped.fazenda.mg.gov.br/portalnfce/sistema/qrcode.xhtml?p="
            + "31260911614938000118650180003378659268089479|2|1|21|73.45|abc|1|DEF";
    private static final String CHAVE = "31260911614938000118650180003378659268089479";

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/sefaz/mg/" + name)
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

    /** Adapter with the two network seams stubbed: GET returns get(), POST returns post(). */
    private static MgNfcePortalAdapter adapter(CaptchaSolver solver,
                                               Function<String, String> get,
                                               Function<String, String> post,
                                               AtomicReference<MultiValueMap<String, Object>> capturedBody) {
        return new MgNfcePortalAdapter(RestClient.builder(), solver, 1000, 3, 0, "test") {
            @Override
            protected PortalPage httpGet(String url) {
                return new PortalPage(get.apply(url), "JSESSIONID=abc123");
            }

            @Override
            protected String httpPostMultipart(String url, MultiValueMap<String, Object> body, String cookieHeader) {
                if (capturedBody != null) capturedBody.set(body);
                return post.apply(url);
            }
        };
    }

    @Test
    void fetchHtml_solvesTurnstileThenPostsForm_returnsDanfe() {
        var body = new AtomicReference<MultiValueMap<String, Object>>();
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"), url -> fixture("danfe.html"), body);

        var danfe = adapter.fetchHtml(QR_URL);

        assertTrue(danfe.contains("SUPERMERCADO BORGES"));
        // POST carried the solved token, the real (dynamic) ViewState + button field.
        assertEquals("turnstile-token", body.get().getFirst("cf-turnstile-response"));
        assertTrue(body.get().containsKey("javax.faces.ViewState"));
        assertEquals("formPrincipal", body.get().getFirst("formPrincipal"));
        assertTrue(body.get().keySet().stream().anyMatch(name -> name.startsWith("formPrincipal:j_idt")));
    }

    @Test
    void fetchHtml_thenParse_yieldsRealMgReceipt() {
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"), url -> fixture("danfe.html"), null);

        var html = adapter.fetchHtml(QR_URL);
        var parsed = adapter.parseHtml(html, CHAVE, QR_URL);

        assertEquals("11614938000118", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("SUPERMERCADO BORGES E MIRANDA"));
        assertEquals(0, new BigDecimal("73.45").compareTo(parsed.totalAmount()));
        assertEquals(6, parsed.items().size());

        var first = parsed.items().get(0);
        assertTrue(first.rawDescription().contains("GEL KANECHOM"));
        assertNull(first.ean(), "MG reports a merchant PLU, not a GTIN");
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("9.99").compareTo(first.totalPrice()));

        var weighed = parsed.items().stream()
                .filter(item -> item.rawDescription().contains("PALETA GROSSA")).findFirst().orElseThrow();
        assertEquals("KG", weighed.unit());
        assertEquals(0, new BigDecimal("1.0177").compareTo(weighed.quantity()));
        assertEquals(0, new BigDecimal("40.28").compareTo(weighed.totalPrice()));

        var itemSum = parsed.items().stream()
                .map(item -> item.totalPrice()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }

    @Test
    void fetchHtml_noSolver_throwsCaptchaUnavailable() {
        var adapter = adapter(NO_SOLVER, url -> fixture("challenge.html"), url -> fixture("danfe.html"), null);

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(QR_URL));
    }

    @Test
    void fetchHtml_rejectedToken_retriesThenThrowsFetch() {
        // POST keeps returning the challenge page → token rejected each attempt.
        var adapter = adapter(TOKEN_SOLVER, url -> fixture("challenge.html"), url -> fixture("challenge.html"), null);

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL));
    }

    @Test
    void fetchHtml_danfeServedWithoutChallenge_returnsDirectly() {
        var adapter = adapter(NO_SOLVER, url -> fixture("danfe.html"), url -> "unused", null);

        assertTrue(adapter.fetchHtml(QR_URL).contains("SUPERMERCADO BORGES"));
    }

    @Test
    void resolveUrl_rejectsNonMgHost() {
        var adapter = adapter(TOKEN_SOLVER, url -> "", url -> "", null);

        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://evil.example.com/qrcode?p=" + CHAVE));
        assertThrows(InvalidQrPayloadException.class, () -> adapter.resolveUrl(CHAVE));
        assertEquals(QR_URL, adapter.resolveUrl(QR_URL));
    }

    @Test
    void extractSubmitButton_readsJsfcljsCall() {
        var button = MgNfcePortalAdapter.extractSubmitButton(fixture("challenge.html"));

        assertEquals("formPrincipal", button.formId());
        assertTrue(button.name().startsWith("formPrincipal:j_idt"));
        assertEquals(button.name(), button.value());
    }
}
