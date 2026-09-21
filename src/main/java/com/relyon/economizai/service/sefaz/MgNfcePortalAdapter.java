package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaSolveFailedException;
import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.privacy.LogMasker;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minas Gerais NFC-e adapter. The SEF/MG portal
 * ({@code portalsped.fazenda.mg.gov.br}) is a JSF (Mojarra/PrimeFaces) page gated
 * by Cloudflare Turnstile: the QR URL GET returns a challenge page carrying a
 * {@code javax.faces.ViewState}, a {@code cf-turnstile} widget and a
 * {@code mojarra.jsfcljs} submit button; solving the Turnstile and replaying the
 * form as a {@code multipart/form-data} POST (with the token, ViewState, form
 * marker and the button field) returns the DANFE.
 *
 * <p>Mirrors {@link SantaCatarinaNfcePortalAdapter}'s Turnstile+re-solve retry
 * shape. Verified against a real MG nota. Prefer this free/cheap-captcha path over
 * the paid Infosimples fallback.
 */
@Slf4j
@Component
public class MgNfcePortalAdapter implements SefazAdapter {

    private static final String HOST = "portalsped.fazenda.mg.gov.br";
    private static final String BASE_URL = "https://" + HOST;
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);
    // The submit button id is JSF-generated (formPrincipal:j_idtNN) and renumbers
    // without notice, so we read it from the mojarra.jsfcljs call, never hardcode it.
    private static final Pattern JSFCLJS = Pattern.compile(
            "jsfcljs\\(document\\.getElementById\\('([^']+)'\\),\\{'([^']+)':'([^']+)'\\}");

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryDelayMs;

    public MgNfcePortalAdapter(RestClient.Builder builder,
                               CaptchaSolver captchaSolver,
                               @Value("${economizai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                               @Value("${economizai.ingestion.sefaz.retry.mg-max-attempts:3}") int maxAttempts,
                               @Value("${economizai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                               @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        this.captchaSolver = captchaSolver;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        log.info("MgNfcePortalAdapter active retry maxAttempts={} delayMs={} captchaSolver configured={}",
                this.maxAttempts, this.retryDelayMs, captchaSolver.isConfigured());
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.MG);
    }

    @Override
    public boolean requiresQrSignature() {
        // The portal consults from the signed QR URL (?p=<chave>|...|<assinatura>);
        // a bare typed chave has no signature to replay, so route those to fallback.
        return true;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt);
            } catch (CaptchaSolveFailedException | RestClientException ex) {
                // Turnstile token rejected or transient network/5xx — re-solve and retry.
                if (attempt >= maxAttempts) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                log.warn("mg.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getClass().getSimpleName(), delay);
                sleep(delay);
            }
            // CaptchaUnavailableException / ReceiptParseException / InvalidQrPayloadException
            // are deterministic — they propagate without being caught here.
        }
        log.warn("mg.fetch.exhausted attempts={}", maxAttempts);
        throw new SefazFetchException(UnidadeFederativa.MG.name());
    }

    private String fetchOnce(String url, int attempt) {
        log.info("mg.fetch attempt={}/{}", attempt, maxAttempts);
        var page = httpGet(url);
        var html = page.body();
        if (html == null || html.isBlank()) {
            throw new RestClientException("mg-empty-response-body");
        }
        if (!looksLikeChallenge(html)) {
            return html; // already the DANFE (portal served it without a challenge)
        }
        return solveAndSubmit(html, url, page.cookieHeader());
    }

    private String solveAndSubmit(String challengeHtml, String currentUrl, String cookieHeader) {
        if (!captchaSolver.isConfigured()) {
            throw new CaptchaUnavailableException(UnidadeFederativa.MG.name());
        }
        var document = Jsoup.parse(challengeHtml, BASE_URL);
        var form = document.selectFirst("form");
        if (form == null) throw new ReceiptParseException("mg-form-missing");
        var siteKey = extractTurnstileSiteKey(document);
        if (siteKey == null) throw new ReceiptParseException("mg-turnstile-sitekey-missing");
        var viewState = form.select("input[name=javax.faces.ViewState]").attr("value");
        if (viewState.isBlank()) throw new ReceiptParseException("mg-viewstate-missing");
        var button = extractSubmitButton(challengeHtml);
        if (button == null) throw new ReceiptParseException("mg-submit-button-missing");
        var action = form.hasAttr("action") ? absoluteUrl(form.attr("action")) : currentUrl;

        log.info("mg.turnstile.solving siteKey={}", siteKey);
        var token = captchaSolver.solveCloudflareTurnstile(siteKey, currentUrl);

        var body = new LinkedMultiValueMap<String, Object>();
        body.add(button.formId(), button.formId());
        body.add(button.name(), button.value());
        body.add("javax.faces.ViewState", viewState);
        body.add("cf-turnstile-response", token);
        var danfe = httpPostMultipart(action, body, cookieHeader);

        if (danfe == null || danfe.isBlank()) {
            throw new SefazFetchException(UnidadeFederativa.MG.name());
        }
        if (looksLikeChallenge(danfe)) {
            throw new CaptchaSolveFailedException("mg-turnstile-rejected");
        }
        return danfe;
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return MgNfceDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    public String resolveUrl(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) throw new InvalidQrPayloadException();
        var trimmed = qrPayload.trim();
        if (!trimmed.toLowerCase().startsWith("http") || !isAllowedMgUrl(trimmed)) {
            log.warn("mg.url.rejected host not portalsped.fazenda.mg.gov.br");
            throw new InvalidQrPayloadException();
        }
        // The QR's p= value uses raw '|' separators, which URI.create rejects as an
        // illegal query character — percent-encode them (the portal decodes back).
        return trimmed.replace("|", "%7C");
    }

    // ── Seams (overridden in tests) ─────────────────────────────────────────────

    protected PortalPage httpGet(String url) {
        var response = restClient.get()
                .uri(URI.create(url))
                .retrieve()
                .toEntity(String.class);
        return new PortalPage(response.getBody(), cookieHeader(response));
    }

    protected String httpPostMultipart(String url, MultiValueMap<String, Object> body, String cookieHeader) {
        return restClient.post()
                .uri(URI.create(url))
                .contentType(MediaType.MULTIPART_FORM_DATA)
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
        return lower.contains("cf-turnstile") || lower.contains("challenges.cloudflare.com/turnstile");
    }

    static String extractTurnstileSiteKey(Document document) {
        var turnstile = document.selectFirst(".cf-turnstile[data-sitekey]");
        if (turnstile == null) return null;
        var siteKey = turnstile.attr("data-sitekey").trim();
        return siteKey.isBlank() ? null : siteKey;
    }

    static SubmitButton extractSubmitButton(String html) {
        var matcher = JSFCLJS.matcher(html);
        if (!matcher.find()) return null;
        return new SubmitButton(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private boolean isAllowedMgUrl(String url) {
        var matcher = URL_HOST.matcher(url);
        if (!matcher.find()) return false;
        var host = matcher.group(2).toLowerCase();
        return host.equals(HOST) || host.endsWith(".fazenda.mg.gov.br");
    }

    private static String absoluteUrl(String value) {
        if (value == null || value.isBlank()) return BASE_URL;
        if (value.toLowerCase().startsWith("http")) return value;
        return value.startsWith("/") ? BASE_URL + value : BASE_URL + "/" + value;
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
            throw new SefazFetchException(UnidadeFederativa.MG.name());
        }
    }

    record SubmitButton(String formId, String name, String value) {
    }

    record PortalPage(String body, String cookieHeader) {
    }
}
