package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SergipeNfcePortalAdapterTest {

    // Real SE chave (cUF=28, mod=65, tpEmis=1), structurally valid.
    private static final String CHAVE_SE = "28261006057223040475650120002442491120227008";
    private static final String SE_QR = "http://www.nfce.se.gov.br/nfce/qrcode?p=" + CHAVE_SE + "|3|1";
    private static final String EXPECTED_SITEKEY = "0x4AAAAAADPVesX-QLrY8PWn";

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/sefaz/se/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static SergipeNfcePortalAdapter adapter(boolean enabled, CaptchaSolver solver) {
        return new SergipeNfcePortalAdapter(RestClient.builder(), solver, enabled,
                EXPECTED_SITEKEY, 30000, 1, 0L, 60000L, "test");
    }

    @Test
    void supportedStates_containsSeWhenEnabled() {
        assertEquals(Set.of(UnidadeFederativa.SE), adapter(true, solver(true)).supportedStates());
    }

    @Test
    void supportedStates_emptyWhenDisabled() {
        assertTrue(adapter(false, solver(true)).supportedStates().isEmpty());
    }

    @Test
    void requiresQrSignature_falseSoBareChaveWorks() {
        assertFalse(adapter(true, solver(true)).requiresQrSignature());
    }

    @Test
    void extractSiteKey_readsTurnstileKeyFromLiveConsultForm() {
        assertEquals(EXPECTED_SITEKEY, adapter(true, solver(true)).extractSiteKey(fixture("consulta-form.html")));
    }

    @Test
    void extractSiteKey_fallsBackToConfiguredDefaultWhenPageHasNone() {
        assertEquals(EXPECTED_SITEKEY, adapter(true, solver(true)).extractSiteKey("<html>no key here</html>"));
    }

    @Test
    void extractSiteKey_nullWhenNeitherPageNorDefaultHasKey() {
        var noDefault = new SergipeNfcePortalAdapter(RestClient.builder(), solver(true), true,
                "", 30000, 1, 0L, 60000L, "test");
        assertNull(noDefault.extractSiteKey("<html>nothing</html>"));
    }

    @Test
    void isCaptchaRejected_trueForLiveRejectionPage() {
        assertTrue(SergipeNfcePortalAdapter.isCaptchaRejected(fixture("captcha-invalido.html")));
    }

    @Test
    void isCaptchaRejected_falseForDanfe() {
        assertFalse(SergipeNfcePortalAdapter.isCaptchaRejected(danfeHtml()));
    }

    @Test
    void fetchHtml_solvesTurnstilePostsChaveAndReturnsDanfe() {
        var solver = solver(true);
        var adapter = new TestSeAdapter(solver, 3);
        adapter.formPage = ResponseEntity.ok()
                .header("Set-Cookie", "JSESSIONID=se-session-1; Path=/portal")
                .body(fixture("consulta-form.html"));
        adapter.postResponse = ResponseEntity.ok(danfeHtml());

        var html = adapter.fetchHtml(SE_QR);

        assertEquals(danfeHtml(), html);
        assertEquals(EXPECTED_SITEKEY, solver.siteKey);
        assertEquals(SergipeNfcePortalAdapter.CONSULT_FORM_URL, solver.pageUrl);
        assertEquals(CHAVE_SE, adapter.postedBody.getFirst("cdChaveAcesso"), "bare chave extracted from SE QR url");
        assertEquals("turnstile-token", adapter.postedBody.getFirst("cf-turnstile-response"));
        assertEquals("", adapter.postedBody.getFirst("dtEmissaoNFCe"), "emission date sent blank (optional)");
        assertEquals("JSESSIONID=se-session-1", adapter.postedCookie, "session cookie forwarded to the POST");
        assertEquals(1, solver.solveCount);
    }

    @Test
    void fetchHtml_acceptsBareChaveDirectly() {
        var solver = solver(true);
        var adapter = new TestSeAdapter(solver, 1);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postResponse = ResponseEntity.ok(danfeHtml());

        adapter.fetchHtml(CHAVE_SE);

        assertEquals(CHAVE_SE, adapter.postedBody.getFirst("cdChaveAcesso"));
    }

    @Test
    void fetchHtml_whenTurnstilePersistentlyRejected_reSolvesThenThrowsFetch() {
        var solver = solver(true);
        var adapter = new TestSeAdapter(solver, 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postResponse = ResponseEntity.ok(fixture("captcha-invalido.html"));

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SE_QR));
        assertEquals(3, adapter.postCount, "re-solves and retries up to maxAttempts");
        assertEquals(3, solver.solveCount, "a FRESH token is solved on each retry");
    }

    @Test
    void fetchHtml_retriesRejectedTokenThenSucceeds() {
        var solver = solver(true);
        var adapter = new TestSeAdapter(solver, 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postResponses.add(ResponseEntity.ok(fixture("captcha-invalido.html")));
        adapter.postResponses.add(ResponseEntity.ok(danfeHtml()));

        var html = adapter.fetchHtml(SE_QR);

        assertEquals(danfeHtml(), html);
        assertEquals(2, adapter.postCount);
    }

    @Test
    void fetchHtml_deterministicClientError_stopsAfterOneSolve() {
        var adapter = new TestSeAdapter(solver(true), 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postFailure = new HttpClientErrorException(HttpStatus.NOT_FOUND);

        assertThrows(SefazDeterministicFetchException.class, () -> adapter.fetchHtml(SE_QR));
        assertEquals(1, adapter.postCount, "a 404 must not buy a second captcha solve");
    }

    @Test
    void fetchHtml_throttledClientError_staysRescuableWithoutRetrying() {
        var adapter = new TestSeAdapter(solver(true), 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postFailure = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);

        var thrown = assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SE_QR));
        assertFalse(thrown instanceof SefazDeterministicFetchException);
        assertEquals(1, adapter.postCount);
    }

    @Test
    void fetchHtml_withoutConfiguredSolver_throwsUnavailableAndNeverPosts() {
        var adapter = new TestSeAdapter(solver(false), 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(SE_QR));
        assertEquals(0, adapter.postCount, "no solver → fail fast, never POST");
    }

    @Test
    void fetchHtml_emptyDanfeBody_retriesThenThrows() {
        var adapter = new TestSeAdapter(solver(true), 3);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postResponse = ResponseEntity.ok("");

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SE_QR));
        assertEquals(3, adapter.postCount);
    }

    @Test
    void fetchHtml_stopsAtTimeBudgetBeforeExhaustingAttempts() {
        var solver = solver(true);
        // maxAttempts=10 but maxTotalMs=0 → deadline already passed after the first
        // rejected token, so it gives up immediately (1 POST), not 10.
        var adapter = new TestSeAdapter(solver, 10, 0L);
        adapter.formPage = ResponseEntity.ok(fixture("consulta-form.html"));
        adapter.postResponse = ResponseEntity.ok(fixture("captcha-invalido.html"));

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SE_QR));
        assertEquals(1, adapter.postCount);
    }

    @Test
    void parseHtml_parsesResponsiveDanfeLayout() {
        var parsed = adapter(true, solver(false)).parseHtml(danfeHtml(), CHAVE_SE, SE_QR);

        assertEquals(CHAVE_SE, parsed.chaveAcesso());
        assertEquals(1, parsed.items().size());
        assertEquals("ARROZ TIO JOAO 5KG", parsed.items().get(0).rawDescription());
        assertEquals(0, new BigDecimal("24.90").compareTo(parsed.items().get(0).totalPrice()));
    }

    private static String danfeHtml() {
        return """
                <html><body>
                  <div id="conteudo"><div class="txtTopo">SUPERMERCADO TESTE SE LTDA</div></div>
                  <table id="tabResult">
                    <tr>
                      <td><span class="txtTit">ARROZ TIO JOAO 5KG</span>
                          <span class="RCod">(Código: 7891234567890)</span>
                          <span class="Rqtd">Qtde.:1</span>
                          <span class="RUN">UN: UN</span>
                          <span class="RvlUnit">Vl. Unit.: 24,90</span></td>
                      <td class="valor">24,90</td>
                    </tr>
                  </table>
                  <span class="totalNumb txtMax">24,90</span>
                </body></html>
                """;
    }

    private static TestCaptchaSolver solver(boolean configured) {
        return new TestCaptchaSolver(configured);
    }

    private static final class TestCaptchaSolver implements CaptchaSolver {
        private final boolean configured;
        private String siteKey;
        private String pageUrl;
        private int solveCount;

        private TestCaptchaSolver(boolean configured) {
            this.configured = configured;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String solveCloudflareTurnstile(String siteKey, String pageUrl) {
            this.siteKey = siteKey;
            this.pageUrl = pageUrl;
            this.solveCount++;
            return "turnstile-token";
        }
    }

    private static final class TestSeAdapter extends SergipeNfcePortalAdapter {
        private ResponseEntity<String> formPage;
        private ResponseEntity<String> postResponse;
        private final ArrayDeque<ResponseEntity<String>> postResponses = new ArrayDeque<>();
        private RuntimeException postFailure;
        private int postCount;
        private MultiValueMap<String, String> postedBody;
        private String postedCookie;

        private TestSeAdapter(CaptchaSolver solver, int maxAttempts) {
            this(solver, maxAttempts, 60000L);
        }

        private TestSeAdapter(CaptchaSolver solver, int maxAttempts, long maxTotalMs) {
            super(RestClient.builder(), solver, true, EXPECTED_SITEKEY, 30000, maxAttempts, 0L, maxTotalMs, "test");
        }

        @Override
        protected ResponseEntity<String> httpGetForm(String url) {
            assertEquals(SergipeNfcePortalAdapter.CONSULT_FORM_URL, url);
            return formPage;
        }

        @Override
        protected ResponseEntity<String> httpPostConsult(String url, MultiValueMap<String, String> body, String cookieHeader) {
            assertEquals(SergipeNfcePortalAdapter.CONSULT_POST_URL, url);
            this.postedBody = body;
            this.postedCookie = cookieHeader;
            this.postCount++;
            if (postFailure != null) throw postFailure;
            return postResponses.isEmpty() ? postResponse : postResponses.poll();
        }
    }
}
