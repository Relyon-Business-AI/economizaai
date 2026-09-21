package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SantaCatarinaNfcePortalAdapterTest {

    private static final String CHAVE_SC = "42260650552333000100650080001188891101255904";
    private static final String SECURITY_URL = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc";
    private static final String FINAL_URL = "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/NFCe_Detalhes.aspx?rq=def";

    @Test
    void supportedStates_containsSc() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(true), 30000, 1, 0L, "test");

        assertEquals(Set.of(UnidadeFederativa.SC), adapter.supportedStates());
    }

    @Test
    void resolveUrl_encodesScPipeSeparatorsForHttpClients() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(true), 30000, 1, 0L, "test");

        assertEquals(
                "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p="
                        + CHAVE_SC + "%7C3%7C1",
                adapter.resolveUrl(CHAVE_SC));
        assertEquals(
                "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p="
                        + CHAVE_SC + "%7C3%7C1",
                adapter.resolveUrl("https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p="
                        + CHAVE_SC + "|3|1"));
    }

    @Test
    void preflightChave_extractsOriginalChaveFromSecurityViewState() {
        var adapter = new TestScAdapter(solver(false));
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));

        var chave = adapter.preflightChave(SECURITY_URL);

        assertEquals(CHAVE_SC, chave.orElseThrow());
    }

    @Test
    void fetchHtml_solvesTurnstilePostsAspNetFormAndFollowsRedirect() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver);
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok()
                .header("Set-Cookie", "ASP.NET_SessionId=session-1; path=/")
                .body(securityHtml()));
        adapter.postResponse = ResponseEntity.status(302)
                .header("Location", FINAL_URL)
                .header("Set-Cookie", "SAT_AUTH=ok; path=/")
                .build();
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok(scDanfeHtml()));

        var html = adapter.fetchHtml(SECURITY_URL);

        assertEquals(scDanfeHtml(), html);
        assertEquals("0x4AAAAAAB2sElvZiQsYMEfK", solver.siteKey);
        assertEquals(SECURITY_URL, solver.pageUrl);
        assertEquals(SECURITY_URL, adapter.postedUrl);
        assertEquals("ASP.NET_SessionId=session-1", adapter.postedCookie);
        assertEquals("_ctl0$_ctl0$Body$Main$ButtonValidar", adapter.postedBody.getFirst("__EVENTTARGET"));
        assertEquals("turnstile-token", adapter.postedBody.getFirst("_ctl0:_ctl0:Body:Main:cf-turnstile-response"));
        assertEquals("turnstile-token", adapter.postedBody.getFirst("cf-turnstile-response"));
        assertEquals("ASP.NET_SessionId=session-1; SAT_AUTH=ok", adapter.redirectCookie);
    }

    @Test
    void fetchHtml_preservesCookiesFromInitialConsultRedirectIntoCaptchaPost() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver);
        var consultUrl = "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p="
                + CHAVE_SC + "%7C3%7C1";
        adapter.getResponses.put(consultUrl, ResponseEntity.status(302)
                .header("Location", SECURITY_URL)
                .header("Set-Cookie", "SC_GATE=gate-1; path=/")
                .build());
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok()
                .header("Set-Cookie", "ASP.NET_SessionId=session-1; path=/")
                .body(securityHtml()));
        adapter.postResponse = ResponseEntity.status(302)
                .header("Location", FINAL_URL)
                .header("Set-Cookie", "SAT_AUTH=ok; path=/")
                .build();
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok(scDanfeHtml()));

        var html = adapter.fetchHtml(consultUrl);

        assertEquals(scDanfeHtml(), html);
        assertEquals("SC_GATE=gate-1; ASP.NET_SessionId=session-1", adapter.postedCookie);
        assertEquals("SC_GATE=gate-1; ASP.NET_SessionId=session-1; SAT_AUTH=ok", adapter.redirectCookie);
    }

    @Test
    void fetchHtml_reSetSessionCookieReplacesStaleValueNotAppends() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver);
        // The security page re-sets ASP.NET_SessionId (Cloudflare rotates it). The
        // outgoing Cookie must carry the NEW value only, not "NAME=old; NAME=new".
        var consultUrl = "https://sat.sef.sc.gov.br/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p="
                + CHAVE_SC + "%7C3%7C1";
        adapter.getResponses.put(consultUrl, ResponseEntity.status(302)
                .header("Location", SECURITY_URL)
                .header("Set-Cookie", "ASP.NET_SessionId=stale; path=/")
                .build());
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok()
                .header("Set-Cookie", "ASP.NET_SessionId=fresh; path=/")
                .body(securityHtml()));
        adapter.postResponse = ResponseEntity.status(302).header("Location", FINAL_URL).build();
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok(scDanfeHtml()));

        adapter.fetchHtml(consultUrl);

        assertEquals("ASP.NET_SessionId=fresh", adapter.postedCookie, "re-set cookie replaces stale value");
    }

    @Test
    void fetchHtml_whenTurnstilePersistentlyRejected_exhaustsRetriesThenThrowsSefazFetch() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver, 3);
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));
        // Portal keeps returning the challenge page (token rejected every time).
        adapter.postResponse = ResponseEntity.ok(securityHtml());

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SECURITY_URL));
        assertEquals(3, adapter.postCount, "should re-solve and retry up to maxAttempts");
    }

    @Test
    void fetchHtml_whenPortalReturnsNonDanfePage_retriesThenThrowsFetch() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver, 3);
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));
        // Turnstile accepted (redirect to the final page), but that page carries no
        // items — a flaky error/interstitial that must be re-fetched, not returned as
        // a doomed 0-item parse.
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok("<html><body>Erro ao processar a consulta</body></html>"));
        adapter.postResponse = ResponseEntity.status(302).header("Location", FINAL_URL).build();

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(SECURITY_URL));
        assertEquals(3, adapter.postCount, "a non-DANFE page should trigger re-fetch up to maxAttempts");
    }

    @Test
    void fetchHtml_retriesRejectedTurnstileThenSucceedsWithFreshToken() {
        var solver = solver(true);
        var adapter = new TestScAdapter(solver, 3);
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));
        adapter.getResponses.put(FINAL_URL, ResponseEntity.ok(scDanfeHtml()));
        // 1st POST: token rejected (challenge page again). 2nd POST: accepted → redirect to DANFE.
        adapter.postResponses.add(ResponseEntity.ok(securityHtml()));
        adapter.postResponses.add(ResponseEntity.status(302).header("Location", FINAL_URL).build());

        var html = adapter.fetchHtml(SECURITY_URL);

        assertEquals(scDanfeHtml(), html);
        assertEquals(2, adapter.postCount, "first token rejected, second succeeded");
    }

    @Test
    void fetchHtml_withoutConfiguredSolverThrowsUnavailable() {
        var adapter = new TestScAdapter(solver(false));
        adapter.getResponses.put(SECURITY_URL, ResponseEntity.ok(securityHtml()));

        assertThrows(CaptchaUnavailableException.class, () -> adapter.fetchHtml(SECURITY_URL));
    }

    @Test
    void parseHtml_parsesScDetailsPage() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(false), 30000, 1, 0L, "test");

        var parsed = adapter.parseHtml(scDanfeHtml(), CHAVE_SC, FINAL_URL);

        assertEquals(CHAVE_SC, parsed.chaveAcesso());
        assertEquals("50552333000100", parsed.cnpjEmitente());
        assertEquals("OTTO ATACAREJO COMERCIAL LTDA", parsed.marketName());
        assertEquals("AV MAXIMILIANO FUERBRINGER SC 486 , 518 , SOUZA CRUZ , BRUSQUE , SC", parsed.marketAddress());
        assertEquals(LocalDateTime.of(2026, 6, 30, 14, 31, 18), parsed.issuedAt());
        assertEquals(new BigDecimal("133.19"), parsed.totalAmount());
        assertEquals(3, parsed.items().size());
        assertEquals("CHOC KIT KAT 4 FNGR DARK 415GR", parsed.items().get(0).rawDescription());
        assertNull(parsed.items().get(0).ean(), "SC item code 28941 is merchant-internal, not an EAN");
        assertEquals(new BigDecimal("2"), parsed.items().get(0).quantity());
        assertEquals("UNID", parsed.items().get(0).unit());
        assertEquals(new BigDecimal("4.49"), parsed.items().get(0).unitPrice());
        assertEquals(new BigDecimal("8.98"), parsed.items().get(0).totalPrice());
    }

    @Test
    void parseHtml_parsesScItemsSplitAcrossTextLines() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(false), 30000, 1, 0L, "test");

        var parsed = adapter.parseHtml(scDanfeHtmlWithSplitItems(), CHAVE_SC, FINAL_URL);

        assertEquals(2, parsed.items().size());
        assertEquals("CHOC KIT KAT 4 FNGR DARK 415GR", parsed.items().get(0).rawDescription());
        assertEquals(new BigDecimal("2"), parsed.items().get(0).quantity());
        assertEquals(new BigDecimal("4.49"), parsed.items().get(0).unitPrice());
        assertEquals(new BigDecimal("8.98"), parsed.items().get(0).totalPrice());
    }

    @Test
    void parseHtml_parsesScItemsFromFlattenedText() {
        var adapter = new SantaCatarinaNfcePortalAdapter(RestClient.builder(), solver(false), 30000, 1, 0L, "test");

        var parsed = adapter.parseHtml(scDanfeHtmlWithFlattenedItems(), CHAVE_SC, FINAL_URL);

        assertEquals(2, parsed.items().size());
        assertEquals("CHOC KIT KAT 4 FNGR DARK 415GR", parsed.items().get(0).rawDescription());
        assertEquals(new BigDecimal("2"), parsed.items().get(0).quantity());
        assertEquals(new BigDecimal("4.49"), parsed.items().get(0).unitPrice());
        assertEquals(new BigDecimal("8.98"), parsed.items().get(0).totalPrice());
    }

    @Test
    void extractChaveFromHtml_parsesPrintedChave() {
        assertEquals(CHAVE_SC, SantaCatarinaNfcePortalAdapter.extractChaveFromHtml(scDanfeHtml()).orElseThrow());
    }

    private static TestCaptchaSolver solver(boolean configured) {
        return new TestCaptchaSolver(configured);
    }

    private static String securityHtml() {
        var original = "~/Sat.Dfe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p=" + CHAVE_SC + "%7C3%7C1";
        var viewState = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
        return """
                <html><body>
                  <form method="post" action="/tax.NET/SecurityVerify.aspx?rq=abc" id="Main">
                    <input type="hidden" name="__VIEWSTATE" value="%s"/>
                    <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN"/>
                    <input type="hidden" name="__EVENTVALIDATION" value="EV"/>
                    <div class="cf-turnstile" data-sitekey="0x4AAAAAAB2sElvZiQsYMEfK"></div>
                    <input type="hidden" name="_ctl0:_ctl0:Body:Main:cf-turnstile-response" id="Body_Main_cf-turnstile-response" value=""/>
                    <a href="javascript:__doPostBack('_ctl0$_ctl0$Body$Main$ButtonValidar','')">Validar</a>
                  </form>
                  Cloudflare
                </body></html>
                """.formatted(viewState);
    }

    private static String scDanfeHtml() {
        return """
                <html><body>
                  <h1>DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR ELETRONICA</h1>
                  <section>
                    OTTO ATACAREJO COMERCIAL LTDA
                    CNPJ: 50.552.333/0001-00
                    AV MAXIMILIANO FUERBRINGER SC 486 , 518 , SOUZA CRUZ , BRUSQUE , SC
                  </section>
                  <div class="item">CHOC KIT KAT 4 FNGR DARK 415GR (Código: 28941 ) Vl. Total 8,98 Qtde.:2 UN: UNIDVl. Unit.: 4,49</div>
                  <div class="item">PAPEL HIG BOB PREMIUM FDUPLA C32 ROLO (Código: 35561 ) Vl. Total 32,99 Qtde.:1 UN: UNID Vl. Unit.: 32,99</div>
                  <div class="item">PAO FRANCES KG (Código: 37 ) Qtde.:0,39 UN: KG Vl. Unit.: 16,99 Vl. Total 6,63</div>
                  <div>Cartão de Débito 133,19</div>
                  <div>Informações gerais da Nota</div>
                  <div>Número: 118889 Série: 8 Emissão: 30/06/2026 14:31:18 - Via Consumidor 2</div>
                  <div>Chave de acesso:</div>
                  <div>4226 0650 5523 3300 0100 6500 8000 1188 8911 0125 5904</div>
                </body></html>
                """;
    }

    private static String scDanfeHtmlWithSplitItems() {
        return """
                <html><body>
                  <h1>DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR ELETRONICA</h1>
                  <span>OTTO ATACAREJO COMERCIAL LTDA</span><br>
                  <span>CNPJ: 50.552.333/0001-00</span><br>
                  <span>CHOC KIT KAT 4 FNGR DARK 415GR (Código: 28941 )</span><br>
                  <span>Vl. Total</span><span>8,98</span><br>
                  <span>Qtde.:2</span><span>UN: UNID</span><span>Vl. Unit.: 4,49</span><br>
                  <span>PAPEL HIG BOB PREMIUM FDUPLA C32 ROLO (Código: 35561 )</span><br>
                  <span>Vl. Total</span><span>32,99</span><br>
                  <span>Qtde.:1 UN: UNID Vl. Unit.: 32,99</span><br>
                  <span>Cartão de Débito 41,97</span>
                </body></html>
                """;
    }

    private static String scDanfeHtmlWithFlattenedItems() {
        return """
                <html><body><span>
                  DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR ELETRONICA
                  OTTO ATACAREJO COMERCIAL LTDA CNPJ: 50.552.333/0001-00
                  CHOC KIT KAT 4 FNGR DARK 415GR (Código: 28941 ) Vl Total 8,98 Qtd.:2 UN: UNIDVl Unit.: 4,49
                  PAPEL HIG BOB PREMIUM FDUPLA C32 ROLO (Código: 35561 ) Valor Total 32,99 Quantidade:1 UN: UNID Valor Unitario: 32,99
                  Cartão de Débito 41,97
                </span></body></html>
                """;
    }

    private static final class TestCaptchaSolver implements CaptchaSolver {
        private final boolean configured;
        private String siteKey;
        private String pageUrl;

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
            return "turnstile-token";
        }
    }

    private static final class TestScAdapter extends SantaCatarinaNfcePortalAdapter {
        private final Map<String, ResponseEntity<String>> getResponses = new HashMap<>();
        private ResponseEntity<String> postResponse;
        // When non-empty, each POST pops the next response — lets tests script a
        // rejected-then-accepted Turnstile sequence across retries.
        private final java.util.ArrayDeque<ResponseEntity<String>> postResponses = new java.util.ArrayDeque<>();
        private int postCount;
        private String postedUrl;
        private String postedCookie;
        private String redirectCookie;
        private MultiValueMap<String, String> postedBody;

        private TestScAdapter(CaptchaSolver solver) {
            this(solver, 1);
        }

        private TestScAdapter(CaptchaSolver solver, int maxAttempts) {
            super(RestClient.builder(), solver, 30000, maxAttempts, 0L, "test");
        }

        @Override
        protected ResponseEntity<String> httpGetResponse(String url) {
            assertTrue(getResponses.containsKey(url), "unexpected GET " + url);
            return getResponses.get(url);
        }

        @Override
        protected ResponseEntity<String> httpGetResponse(String url, String cookieHeader) {
            assertTrue(getResponses.containsKey(url), "unexpected GET " + url);
            this.redirectCookie = cookieHeader;
            return getResponses.get(url);
        }

        @Override
        protected ResponseEntity<String> httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
            this.postedUrl = url;
            this.postedBody = body;
            this.postedCookie = cookieHeader;
            this.postCount++;
            return postResponses.isEmpty() ? postResponse : postResponses.poll();
        }
    }
}
