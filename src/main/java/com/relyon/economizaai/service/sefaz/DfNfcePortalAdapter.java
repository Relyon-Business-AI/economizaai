package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Distrito Federal NFC-e adapter. The SEFAZ-DF viewer
 * ({@code ww1.receita.fazenda.df.gov.br/DecVisualizador/Nfce/Captcha?Chave=<chave>})
 * gates the DANFE behind a Cloudflare Turnstile (rendered in reCAPTCHA-compat mode,
 * so the token posts back as {@code g-recaptcha-response}). We GET the captcha page
 * BY CHAVE, solve the Turnstile, and replay the form as an
 * {@code application/x-www-form-urlencoded} POST ({@code Chave} + the token) to get
 * the DANFE. Simpler than {@link MgNfcePortalAdapter} (no JSF ViewState/button).
 *
 * <p>Before this adapter, DF had no dedicated adapter: the experimental catch-all
 * ({@link GenericQrPortalAdapter}) solved the Turnstile but could only resubmit it
 * as a URL query param (a guess), which DF rejects — so every DF nota fell through
 * to device-fetch (where a phone can't solve the captcha) and failed. Mirrors
 * {@link SantaCatarinaNfcePortalAdapter}'s Turnstile + re-solve retry shape.
 * Consults by bare chave, so {@link #requiresQrSignature()} is false.
 */
@Slf4j
@Component
public class DfNfcePortalAdapter implements SefazAdapter {

    private static final String HOST = "ww1.receita.fazenda.df.gov.br";
    private static final String BASE_URL = "https://" + HOST;
    private static final String CAPTCHA_URL = BASE_URL + "/DecVisualizador/Nfce/Captcha?Chave=";

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final long maxTotalMs;

    public DfNfcePortalAdapter(RestClient.Builder builder,
                               CaptchaSolver captchaSolver,
                               @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                               @Value("${economizaai.ingestion.sefaz.retry.df-max-attempts:3}") int maxAttempts,
                               @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                               @Value("${economizaai.ingestion.sefaz.retry.df-max-total-ms:180000}") long maxTotalMs,
                               @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        this.captchaSolver = captchaSolver;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.maxTotalMs = Math.max(0, maxTotalMs);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        log.info("DfNfcePortalAdapter active retry maxAttempts={} delayMs={} maxTotalMs={} captchaSolver configured={}",
                this.maxAttempts, this.retryDelayMs, this.maxTotalMs, captchaSolver.isConfigured());
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.DF);
    }

    @Override
    public boolean requiresQrSignature() {
        // The DF viewer consults by bare chave (?Chave=<44>), so a manually-typed
        // chave works too — no QR signature needed.
        return false;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        if (chave == null || chave.length() != 44) {
            throw new InvalidQrPayloadException();
        }
        var url = CAPTCHA_URL + chave;
        // Wall-clock deadline on top of maxAttempts: each attempt can hold the ingest
        // thread for a full captcha solve, so without it the adapter outlives the
        // ProcessingReceiptSweeper timeout. Mirrors MsDfePortalAdapter.
        var deadlineNanos = System.nanoTime() + maxTotalMs * 1_000_000L;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, chave, attempt);
            } catch (CaptchaSolveFailedException | RestClientException ex) {
                // Turnstile token rejected or transient network/5xx — re-solve and retry.
                if (attempt >= maxAttempts || System.nanoTime() >= deadlineNanos) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                if (System.nanoTime() + delay * 1_000_000L >= deadlineNanos) break;
                log.warn("df.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getClass().getSimpleName(), delay);
                sleep(delay);
            }
            // CaptchaUnavailableException / ReceiptParseException / InvalidQrPayloadException
            // are deterministic — they propagate without being caught here.
        }
        log.warn("df.fetch.exhausted attempts<={} maxTotalMs={}", maxAttempts, maxTotalMs);
        throw new SefazFetchException(UnidadeFederativa.DF.name());
    }

    private String fetchOnce(String url, String chave, int attempt) {
        log.info("df.fetch attempt={}/{}", attempt, maxAttempts);
        var page = httpGet(url);
        var html = page.body();
        if (html == null || html.isBlank()) {
            throw new RestClientException("df-empty-response-body");
        }
        if (!looksLikeChallenge(html)) {
            return html; // already the DANFE (portal served it without a challenge)
        }
        return solveAndSubmit(html, url, chave, page.cookieHeader());
    }

    private String solveAndSubmit(String challengeHtml, String url, String chave, String cookieHeader) {
        if (!captchaSolver.isConfigured()) {
            throw new CaptchaUnavailableException(UnidadeFederativa.DF.name());
        }
        var document = Jsoup.parse(challengeHtml, BASE_URL);
        var siteKey = extractSiteKey(document);
        if (siteKey == null) {
            throw new ReceiptParseException("df-turnstile-sitekey-missing");
        }
        log.info("df.turnstile.solving siteKey={}", siteKey);
        var token = captchaSolver.solveCloudflareTurnstile(siteKey, url);

        var body = new LinkedMultiValueMap<String, String>();
        body.add("Chave", chave);
        // Turnstile runs in compat=recaptcha mode, so the token field is g-recaptcha-response;
        // send the native cf-turnstile-response too for safety.
        body.add("g-recaptcha-response", token);
        body.add("cf-turnstile-response", token);
        var danfe = httpPostForm(url, body, cookieHeader);

        if (danfe == null || danfe.isBlank()) {
            throw new SefazFetchException(UnidadeFederativa.DF.name());
        }
        if (looksLikeChallenge(danfe)) {
            throw new CaptchaSolveFailedException("df-turnstile-rejected");
        }
        return danfe;
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return DfNfceDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    // ── Seams (overridden in tests) ─────────────────────────────────────────────

    protected PortalPage httpGet(String url) {
        var response = restClient.get()
                .uri(URI.create(url))
                .retrieve()
                .toEntity(String.class);
        return new PortalPage(response.getBody(), cookieHeader(response));
    }

    protected String httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
        return restClient.post()
                .uri(URI.create(url))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                    headers.set("Origin", BASE_URL);
                    headers.set("Referer", url);
                })
                .body(body)
                .retrieve()
                .toEntity(String.class)
                .getBody();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    static boolean looksLikeChallenge(String html) {
        if (html == null) return false;
        var lower = html.toLowerCase();
        return lower.contains("challenges.cloudflare.com/turnstile") || lower.contains("<title>captcha</title>");
    }

    static String extractSiteKey(Document document) {
        var element = document.selectFirst("[data-sitekey]");
        if (element == null) return null;
        var siteKey = element.attr("data-sitekey").trim();
        return siteKey.isBlank() ? null : siteKey;
    }

    private static String cookieHeader(ResponseEntity<String> response) {
        var setCookies = response.getHeaders().get("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) return null;
        return setCookies.stream()
                .map(cookie -> cookie.split(";")[0])
                .collect(Collectors.joining("; "));
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(UnidadeFederativa.DF.name());
        }
    }

    record PortalPage(String body, String cookieHeader) {
    }
}
