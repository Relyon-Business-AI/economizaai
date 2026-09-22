package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.ExperimentalCaptchaWallException;
import com.relyon.economizaai.exception.ExperimentalPortalFetchException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericQrPortalAdapterTest {

    // UF code 29 → BA (no dedicated adapter).
    private static final String CHAVE_BA = "29260412345678000190650010000123451123456780";
    private static final String QR_URL_BA = "https://nfe.sefaz.ba.gov.br/servicos/nfce/qrcode.aspx?p="
            + CHAVE_BA + "|2|1|1|deadbeef";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    private static CaptchaSolver solverReturning(String token) {
        return new CaptchaSolver() {
            @Override public boolean isConfigured() { return true; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) { return token; }
            @Override public String solveCloudflareTurnstile(String siteKey, String pageUrl) { return token; }
        };
    }

    private GenericQrPortalAdapter adapter(int maxAttempts, Function<String, String> http) {
        return adapter(maxAttempts, NO_CAPTCHA, http);
    }

    private GenericQrPortalAdapter adapter(int maxAttempts, CaptchaSolver captchaSolver,
                                           Function<String, String> http) {
        return new GenericQrPortalAdapter(RestClient.builder(), captchaSolver, 1000, "test", true, maxAttempts, 0, "gov.br") {
            @Override
            protected String httpGet(String url) {
                return http.apply(url);
            }
        };
    }

    // A real reCAPTCHA v2 site key is exactly 40 chars (Google's public test key).
    private static final String VALID_SITE_KEY = "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI";
    private static final String CAPTCHA_PAGE =
            "<html><div class=\"g-recaptcha\" data-sitekey=\"" + VALID_SITE_KEY + "\"></div></html>";

    @Test
    void resolveUrl_acceptsGovBrHostsOnly() {
        var adapter = adapter(1, url -> "<html/>");

        assertEquals(QR_URL_BA, adapter.resolveUrl(QR_URL_BA));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://evil.example.com/qrcode?p=" + CHAVE_BA));
        assertThrows(InvalidQrPayloadException.class,
                () -> adapter.resolveUrl("https://gov.br.evil.com/qrcode?p=" + CHAVE_BA));
        assertThrows(InvalidQrPayloadException.class, () -> adapter.resolveUrl(CHAVE_BA));
    }

    @Test
    void fetchHtml_returnsBodyOnSuccess() {
        var adapter = adapter(1, url -> "<html>danfe</html>");

        assertEquals("<html>danfe</html>", adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_retriesTransientFailuresThenSucceeds() {
        var calls = new AtomicInteger();
        var adapter = adapter(3, url -> {
            if (calls.incrementAndGet() < 3) throw new RestClientException("timeout");
            return "<html>ok</html>";
        });

        assertEquals("<html>ok</html>", adapter.fetchHtml(QR_URL_BA));
        assertEquals(3, calls.get());
    }

    @Test
    void fetchHtml_exhaustedRetriesThrowsSefazFetch() {
        var adapter = adapter(2, url -> {
            throw new RestClientException("down");
        });

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_clientErrorIsDeterministicNoRetry() {
        var calls = new AtomicInteger();
        var adapter = adapter(3, url -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
        });

        assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));
        assertEquals(1, calls.get());
    }

    @Test
    void fetchHtml_captchaWall_noSolver_signalsRescuableWithEvidence() {
        var adapter = adapter(1, url -> CAPTCHA_PAGE);

        var thrown = assertThrows(ExperimentalCaptchaWallException.class, () -> adapter.fetchHtml(QR_URL_BA));

        assertTrue(thrown instanceof CaptchaUnavailableException);
        assertTrue(thrown.portalEvidence().contains("RECAPTCHA_V2"));
        assertTrue(thrown.portalEvidence().contains(VALID_SITE_KEY));
    }

    @Test
    void fetchHtml_captchaWall_solverConfigured_solvesAndReturnsDanfe() {
        var adapter = adapter(1, solverReturning("solved-token"),
                url -> url.contains("g-recaptcha-response=solved-token") ? "<html>danfe</html>" : CAPTCHA_PAGE);

        assertEquals("<html>danfe</html>", adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_captchaWall_malformedSiteKey_skipsSolverAndSignalsWall() {
        // MG's portal carries a placeholder key whose length != 40. Sending it to the
        // solver is a guaranteed paid-call rejection, so we must NOT call the solver.
        var page = "<html><div class=\"g-recaptcha\" data-sitekey=\"site-key-123\"></div></html>";
        var throwingSolver = new CaptchaSolver() {
            @Override public boolean isConfigured() { return true; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
                throw new AssertionError("solver must not be called for a malformed site key");
            }
            @Override public String solveCloudflareTurnstile(String siteKey, String pageUrl) {
                throw new AssertionError("solver must not be called for a malformed site key");
            }
        };
        var adapter = adapter(1, throwingSolver, url -> page);

        var thrown = assertThrows(ExperimentalCaptchaWallException.class, () -> adapter.fetchHtml(QR_URL_BA));
        assertTrue(thrown instanceof CaptchaUnavailableException);
    }

    @Test
    void fetchHtml_captchaWall_picksValidSiteKeyAmongDecoys() {
        // A decoy short key appears before the real 40-char key — must pick the valid one.
        var page = "<html><div data-sitekey=\"decoy\"></div>"
                + "<div class=\"g-recaptcha\" data-sitekey=\"" + VALID_SITE_KEY + "\"></div></html>";
        var seenKey = new AtomicReference<String>();
        var capturingSolver = new CaptchaSolver() {
            @Override public boolean isConfigured() { return true; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
                seenKey.set(siteKey);
                return "solved-token";
            }
            @Override public String solveCloudflareTurnstile(String siteKey, String pageUrl) { return "solved-token"; }
        };
        var adapter = adapter(1, capturingSolver,
                url -> url.contains("g-recaptcha-response=solved-token") ? "<html>danfe</html>" : page);

        assertEquals("<html>danfe</html>", adapter.fetchHtml(QR_URL_BA));
        assertEquals(VALID_SITE_KEY, seenKey.get());
    }

    @Test
    void fetchHtml_captchaWall_portalRejectsSolvedToken_fallsToWallSignal() {
        // Resubmit comes back as the captcha page again — the guessed resubmit
        // shape didn't work; must degrade to the rescuable wall signal.
        var adapter = adapter(1, solverReturning("solved-token"), url -> CAPTCHA_PAGE);

        assertThrows(ExperimentalCaptchaWallException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_captchaWall_solveThrows_fallsToWallSignal() {
        var failingSolver = new CaptchaSolver() {
            @Override public boolean isConfigured() { return true; }
            @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
                throw new CaptchaSolveFailedException("BA");
            }
        };
        var adapter = adapter(1, failingSolver, url -> CAPTCHA_PAGE);

        assertThrows(ExperimentalCaptchaWallException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void fetchHtml_clientErrorCarriesStatusAndBodyEvidence() {
        var adapter = adapter(1, url -> {
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                    "consulta indisponivel".getBytes(), null);
        });

        var thrown = assertThrows(ExperimentalPortalFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));

        assertTrue(thrown.portalEvidence().contains("404"));
        assertTrue(thrown.portalEvidence().contains("consulta indisponivel"));
    }

    /**
     * The whole premise of the experimental chain: an unknown state whose portal
     * renders the shared responsive-DANFE layout parses with NO dedicated
     * adapter. Locked with the real PR fixture (PR has its own portal — exactly
     * the situation a new Tier-1 state would be in).
     */
    @Test
    void parseHtml_parsesResponsiveDanfeFromUnknownPortal() throws Exception {
        var html = new ClassPathResource("fixtures/sefaz/pr/nfce-real-raiadrogasil.html")
                .getContentAsString(StandardCharsets.UTF_8);
        var chave = "41260361585865261893650030000564031777660148";
        var adapter = adapter(1, url -> html);

        var parsed = adapter.parseHtml(html, chave, "https://www.fazenda.pr.gov.br/nfce/qrcode?p=" + chave);

        assertEquals(chave, parsed.chaveAcesso());
        assertTrue(parsed.items().size() >= 1);
    }

    private GenericQrPortalAdapter adapterWithExchange(Function<String, ResponseEntity<String>> exchange) {
        return new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", true, 1, 0, "gov.br") {
            @Override
            protected ResponseEntity<String> exchange(String url) {
                return exchange.apply(url);
            }
        };
    }

    /**
     * PE (real case, carlabarbosatk 2026-09-19): the portal 301-redirects
     * http:80 -> https:444 — a cross-scheme hop the JDK client won't follow — and
     * then serves the NFe XML. We follow it ourselves and route XML to the XML parser.
     */
    @Test
    void fetchHtml_followsCrossSchemeRedirectAndParsesXml() throws Exception {
        var chave = "26260942591651264205650010000777891671062850";
        var httpQr = "http://nfce.sefaz.pe.gov.br/nfce/consulta?p=" + chave + "|3|1";
        var httpsTarget = "https://nfce.sefaz.pe.gov.br:444/nfce/consulta?p=" + chave + "|3|1";
        var xml = new ClassPathResource("fixtures/sefaz/pe/pe-mcdonalds-3items.xml")
                .getContentAsString(StandardCharsets.UTF_8);
        var adapter = adapterWithExchange(url -> url.startsWith("http://")
                ? ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, httpsTarget).body("<html>moved</html>")
                : ResponseEntity.ok(xml));

        var body = adapter.fetchHtml(httpQr);
        var parsed = adapter.parseHtml(body, chave, httpQr);

        assertEquals(3, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("31.90")));
    }

    @Test
    void httpGet_rejectsRedirectOffGovBr() {
        var adapter = adapterWithExchange(url ->
                ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example.com/steal").body("stub"));

        assertThrows(InvalidQrPayloadException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }

    @Test
    void httpGet_boundsRedirectLoops() {
        var adapter = adapterWithExchange(url ->
                ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://nfe.sefaz.ba.gov.br:444/loop").body("stub"));

        assertThrows(ExperimentalPortalFetchException.class, () -> adapter.fetchHtml(QR_URL_BA));
    }
}
