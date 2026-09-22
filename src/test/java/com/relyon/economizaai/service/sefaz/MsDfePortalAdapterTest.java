package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MS adapter — the captcha-orchestration (detect → solve → parse) is testable
 * with the real captcha-page fixture even though we can't reach a live DANFE.
 * The post-captcha page is stubbed with a known responsive-DANFE fixture to
 * prove parse delegation works once a solver is wired.
 */
class MsDfePortalAdapterTest {

    private static final String MS_CHAVE = "50260677863223012709650190004048511190344086";

    private String captchaPage() throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/ms/nfce-captcha-page.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String rsDanfe() throws Exception {
        try (var stream = new ClassPathResource("fixtures/sefaz/rs/nfce-sample.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CaptchaSolver solver(boolean configured, String token) {
        return new CaptchaSolver() {
            @Override public boolean isConfigured() { return configured; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
                if (!configured) throw new CaptchaUnavailableException("MS");
                return token;
            }
        };
    }

    @Test
    void supportedStates_isMs() {
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, 1, 0L, 60000L, "test");
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.MS));
        assertEquals(1, adapter.supportedStates().size());
    }

    @Test
    void captchaPage_detectedAndSiteKeyExtracted() throws Exception {
        var html = captchaPage();
        assertTrue(MsDfePortalAdapter.looksLikeCaptcha(html));
        assertEquals("6Ld92rYUAAAAAEDBMjM9PhWRkjc10WuVFBOOYV0O", MsDfePortalAdapter.extractSiteKey(html));
    }

    @Test
    void extractsChaveAndEnvFieldNamesDynamically_toleratingJsfIdDrift() {
        // The portal re-numbers its JSF component IDs (j_idtNN); the chave input
        // is identified by maxlength=44, the environment dropdown by <select>.
        var html = """
                <form name="formListar">
                  <select name="formListar:j_idt99" class="form-control">
                    <option value="1">Producao</option><option value="2">Homologacao</option>
                  </select>
                  <input type="text" name="formListar:j_idt77" alt="Chave de Acesso" maxlength="44" size="44" />
                  <a id="formListar:enter" href="#">Consultar</a>
                </form>
                """;
        assertEquals("formListar:j_idt77", MsDfePortalAdapter.extractChaveFieldName(html));
        assertEquals("formListar:j_idt99", MsDfePortalAdapter.extractEnvSelectName(html));
    }

    @Test
    void extractChaveFieldName_returnsNullWhenAbsent() {
        var html = "<form name=\"formListar\"><input type=\"text\" name=\"formListar:other\" /></form>";
        assertNull(MsDfePortalAdapter.extractChaveFieldName(html));
    }

    @Test
    void fetchHtml_noSolverConfigured_failsFastWithCaptchaUnavailable() throws Exception {
        var captcha = captchaPage();
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, 1, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(captcha); }
        };

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(MS_CHAVE));
    }

    @Test
    void fetchHtml_solverConfigured_solvesThenReturnsDanfe() throws Exception {
        var captcha = captchaPage();
        var danfe = rsDanfe();
        var solveCalls = new AtomicInteger();
        var adapter = new MsDfePortalAdapter(RestClient.builder(),
                solver(true, "the-token"), "MS", 5000, 1, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(captcha); }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                solveCalls.incrementAndGet();
                assertEquals("the-token", recaptchaToken);
                assertEquals(MS_CHAVE, chave);
                return danfe;
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);
        var parsed = adapter.parseHtml(html, MS_CHAVE, null);

        assertEquals(1, solveCalls.get());
        assertEquals(MS_CHAVE, parsed.chaveAcesso());
        assertTrue(parsed.items().size() >= 1);
        assertEquals(0, new BigDecimal("99.48").compareTo(parsed.totalAmount()));
    }

    @Test
    void fetchHtml_noCaptchaOnPage_returnsItDirectly() throws Exception {
        var danfe = rsDanfe();
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 1, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) { return ResponseEntity.ok(danfe); }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                throw new AssertionError("should not resubmit when no captcha present");
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);
        assertEquals(danfe, html);
    }

    @Test
    void parseHtml_delegatesToResponsiveDanfeParser() throws Exception {
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(false, null), "MS", 5000, 1, 0L, 60000L, "test");
        var parsed = adapter.parseHtml(rsDanfe(), MS_CHAVE, null);
        assertTrue(parsed.marketName().contains("ZAFFARI"));
    }

    @Test
    void fetchHtml_retriesTransientFailureThenSucceeds() throws Exception {
        var danfe = rsDanfe();
        var getCalls = new AtomicInteger();
        // maxAttempts=3, no delay: first GET throws a transient IO error, second succeeds.
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 3, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) {
                if (getCalls.incrementAndGet() == 1) {
                    throw new ResourceAccessException("I/O error: www.dfe.ms.gov.br");
                }
                return ResponseEntity.ok(danfe); // no captcha marker → returned directly
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);

        assertEquals(danfe, html);
        assertEquals(2, getCalls.get(), "should have retried once after the transient failure");
    }

    @Test
    void fetchHtml_captchaRejectedByPortal_retriesThenSucceeds() throws Exception {
        var captchaHtml = captchaPage();
        var danfe = rsDanfe();
        var solveCalls = new AtomicInteger();
        // maxAttempts=3: first fetchAuthorizedDanfe returns captcha (rejection), second returns DANFE.
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 3, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) {
                return ResponseEntity.ok(captchaHtml);
            }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                return solveCalls.incrementAndGet() == 1 ? captchaHtml : danfe;
            }
        };

        var html = adapter.fetchHtml(MS_CHAVE);

        assertEquals(danfe, html);
        assertEquals(2, solveCalls.get(), "should retry once after captcha was rejected");
    }

    @Test
    void fetchHtml_captchaRejectedByPortal_exhaustsRetriesThenThrowsSefazFetch() throws Exception {
        var captchaHtml = captchaPage();
        // Always returns captcha HTML (portal always rejects) — should exhaust maxAttempts.
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 3, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) {
                return ResponseEntity.ok(captchaHtml);
            }
            @Override protected String fetchAuthorizedDanfe(String chave, String captchaPageHtml,
                                                            String recaptchaToken, String cookieHeader) {
                return captchaHtml; // portal keeps rejecting
            }
        };

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(MS_CHAVE));
    }

    @Test
    void fetchHtml_exhaustsRetriesThenThrowsSefazFetch() throws Exception {
        var getCalls = new AtomicInteger();
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 3, 0L, 60000L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) {
                getCalls.incrementAndGet();
                throw new ResourceAccessException("I/O error: www.dfe.ms.gov.br");
            }
        };

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(MS_CHAVE));
        assertEquals(3, getCalls.get(), "should try up to maxAttempts before giving up");
    }

    @Test
    void fetchHtml_stopsAtTimeBudgetBeforeExhaustingAttempts() throws Exception {
        var getCalls = new AtomicInteger();
        // maxAttempts=10 but maxTotalMs=0 → the deadline is already passed after the
        // first failed attempt, so it must give up immediately (1 call), not 10.
        var adapter = new MsDfePortalAdapter(RestClient.builder(), solver(true, "tok"), "MS", 5000, 10, 0L, 0L, "test") {
            @Override protected ResponseEntity<String> httpGetResponse(String url) {
                getCalls.incrementAndGet();
                throw new ResourceAccessException("I/O error: www.dfe.ms.gov.br");
            }
        };

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(MS_CHAVE));
        assertEquals(1, getCalls.get(), "time budget must stop retries well before maxAttempts");
    }
}
