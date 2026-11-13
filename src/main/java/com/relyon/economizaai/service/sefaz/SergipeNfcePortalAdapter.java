package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NFC-e adapter for Sergipe, whose consult portal
 * ({@code nfce.sefaz.se.gov.br/portal}) gates the DANFE behind a Cloudflare
 * Turnstile and consults by the <b>bare 44-digit chave</b> (no QR signature) —
 * so both a scanned QR ({@code .../nfce/qrcode?p=chave|3|1}) and a manually
 * typed/OCR'd chave work here. Infosimples is down for SE (code 615), so this
 * native scraper is the only path that unblocks the state.
 *
 * <p>Flow (verified live 2026-11-13 against the portal):
 * <ol>
 *   <li>GET {@code portalNoticias.jsp?jsp=barra-menu/servicos/consultaDANFENFCe.htm}
 *       — seeds the {@code JSESSIONID} and renders the consult form. The form is
 *       plain JSP (not ASP.NET postback — the page's {@code __VIEWSTATE} belongs
 *       to the surrounding {@code form1}, NOT to {@code form1ExibirDANFENFCe}),
 *       so no ViewState is needed on the POST.</li>
 *   <li>Solve the Cloudflare Turnstile. The sitekey is injected by page JS
 *       ({@code $('#rcaptcha').attr('data-sitekey', '0x…')}), so it is read from
 *       the page by the stable {@code 0x…} shape and falls back to the configured
 *       default — never a hardcoded auto-generated id, per project rules.</li>
 *   <li>POST {@code consultarNFCeDCA.jsp} with {@code cdChaveAcesso} +
 *       {@code cf-turnstile-response} (+ the optional {@code dtEmissaoNFCe}),
 *       carrying the session cookie → the DANFE page.</li>
 * </ol>
 *
 * <p>The emission date field ({@code dtEmissaoNFCe}) is OPTIONAL — confirmed
 * live: a POST with an empty date and no token returns only "Captcha inválido",
 * never a date-required error, and the SE QR payload carries no {@code dhEmi}.
 * We therefore send it blank; the portal resolves the nota from the chave alone.
 *
 * <p>Wrapped in the mandatory captcha RETRY loop (re-solves a FRESH Turnstile
 * token when the portal rejects it), bounded by {@code se-max-attempts} and a
 * {@code se-max-total-ms} wall-clock deadline, exactly like
 * {@link SantaCatarinaNfcePortalAdapter} / {@link MsDfePortalAdapter}. Transient
 * failures (5xx/timeout/empty body/captcha-rejected) retry; deterministic ones
 * (4xx bad chave, no solver, sitekey missing) propagate without buying a second
 * solve.
 *
 * <p><b>DANFE layout UNVERIFIED:</b> the post-Turnstile DANFE page could not be
 * rendered from this build environment (the CapSolver key is a Render secret not
 * reachable locally), so {@link #parseHtml} provisionally uses the shared
 * {@link ResponsiveDanfeParser}. Final verification needs one real SE NFC-e
 * solved against the live portal (see DEV_NOTES.md). If SE's layout differs, the
 * ingestion chain's LLM rescue still reads the HTML, so SE is not hard-broken.
 */
@Slf4j
@Component
public class SergipeNfcePortalAdapter implements SefazAdapter {

    private static final String BASE_URL = "https://nfce.sefaz.se.gov.br/portal";
    static final String CONSULT_FORM_URL =
            BASE_URL + "/portalNoticias.jsp?jsp=barra-menu/servicos/consultaDANFENFCe.htm";
    static final String CONSULT_POST_URL = BASE_URL + "/consultarNFCeDCA.jsp";
    // A Cloudflare Turnstile sitekey is a stable, human-stable token starting with
    // "0x" — unlike the JSF/ASP.NET auto-ids the project forbids hardcoding, so it
    // is safe to read by shape (and to keep a configured default).
    private static final Pattern TURNSTILE_SITE_KEY = Pattern.compile("0x[A-Za-z0-9_-]{10,}");

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final boolean enabled;
    private final String defaultSiteKey;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final long maxTotalMs;

    public SergipeNfcePortalAdapter(RestClient.Builder builder,
                                    CaptchaSolver captchaSolver,
                                    @Value("${economizaai.ingestion.sefaz.se.enabled:true}") boolean enabled,
                                    @Value("${economizaai.ingestion.sefaz.se.turnstile-sitekey:0x4AAAAAADPVesX-QLrY8PWn}") String defaultSiteKey,
                                    @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                    @Value("${economizaai.ingestion.sefaz.retry.se-max-attempts:3}") int maxAttempts,
                                    @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                    @Value("${economizaai.ingestion.sefaz.retry.se-max-total-ms:180000}") long maxTotalMs,
                                    @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        this.captchaSolver = captchaSolver;
        this.enabled = enabled;
        this.defaultSiteKey = defaultSiteKey == null ? "" : defaultSiteKey.trim();
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
        log.info("SergipeNfcePortalAdapter enabled={} retry maxAttempts={} delayMs={} maxTotalMs={} captchaSolver configured={}",
                enabled, this.maxAttempts, this.retryDelayMs, this.maxTotalMs, captchaSolver.isConfigured());
    }

    /**
     * Claims SE only when enabled. Disabling (SEFAZ_SE_ENABLED=false) drops SE to
     * the experimental/Infosimples chain — a kill switch while the DANFE layout
     * is still being verified.
     */
    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return enabled ? EnumSet.of(UnidadeFederativa.SE) : EnumSet.noneOf(UnidadeFederativa.class);
    }

    /** SE consults by bare chave (QR signature not required), so manual/OCR chaves work too. */
    @Override
    public boolean requiresQrSignature() {
        return false;
    }

    /**
     * Fetches the DANFE, re-solving a rejected Turnstile token on each retry and
     * retrying transient portal errors up to {@code maxAttempts} / {@code maxTotalMs}.
     * Deterministic failures (4xx, no solver, sitekey missing) propagate immediately.
     * Mirrors {@link SantaCatarinaNfcePortalAdapter}.
     */
    @Override
    public String fetchHtml(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        var deadlineNanos = System.nanoTime() + maxTotalMs * 1_000_000L;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(chave, attempt);
            } catch (HttpClientErrorException ex) {
                // 4xx — re-solving won't change the answer; 403/429 stay rescuable, the rest is deterministic.
                log.warn("se.fetch.client_error status={} attempt={}/{}", ex.getStatusCode(), attempt, maxAttempts);
                throw PortalClientErrorClassifier.classify(ex, UnidadeFederativa.SE.name());
            } catch (CaptchaSolveFailedException | RestClientException ex) {
                // Turnstile rejected / transient network / empty body — re-solve and retry.
                if (attempt >= maxAttempts || System.nanoTime() >= deadlineNanos) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                if (System.nanoTime() + delay * 1_000_000L >= deadlineNanos) break;
                log.warn("se.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getClass().getSimpleName(), delay);
                sleep(delay);
            }
            // CaptchaUnavailableException / ReceiptParseException / InvalidQrPayloadException
            // are deterministic — they propagate without being caught here.
        }
        log.warn("se.fetch.exhausted attempts<={} maxTotalMs={} chave={}", maxAttempts, maxTotalMs, LogMasker.chave(chave));
        throw new SefazFetchException(UnidadeFederativa.SE.name());
    }

    private String fetchOnce(String chave, int attempt) {
        log.info("se.fetch attempt={}/{} chave={}", attempt, maxAttempts, LogMasker.chave(chave));
        var formPage = httpGetForm(CONSULT_FORM_URL);
        var formHtml = formPage.getBody();
        if (formHtml == null || formHtml.isBlank()) {
            throw new RestClientException("se-empty-form-page");
        }
        if (!captchaSolver.isConfigured()) {
            log.info("se.captcha.unavailable chave={} (no solver configured)", LogMasker.chave(chave));
            throw new CaptchaUnavailableException(UnidadeFederativa.SE.name());
        }
        var siteKey = extractSiteKey(formHtml);
        if (siteKey == null) {
            log.warn("se.captcha.no_sitekey chave={}", LogMasker.chave(chave));
            throw new ReceiptParseException("turnstile-sitekey-missing");
        }
        var cookieHeader = cookieHeader(formPage.getHeaders().get("Set-Cookie"));
        log.info("se.turnstile.solving chave={} siteKey={}", LogMasker.chave(chave), siteKey);
        var token = captchaSolver.solveCloudflareTurnstile(siteKey, CONSULT_FORM_URL);
        var danfe = submitConsult(chave, token, cookieHeader);
        if (danfe == null || danfe.isBlank()) {
            throw new RestClientException("se-empty-danfe-body");
        }
        if (isCaptchaRejected(danfe)) {
            log.warn("se.captcha.rejected chave={}", LogMasker.chave(chave));
            throw new RestClientException("se-captcha-rejected");
        }
        return danfe;
    }

    private String submitConsult(String chave, String turnstileToken, String cookieHeader) {
        var body = new LinkedMultiValueMap<String, String>();
        body.set("cdChaveAcesso", chave);
        // dtEmissaoNFCe is optional (confirmed live) — the SE QR carries no dhEmi and
        // the receipt has no stored emission date, so we send it blank and let the
        // portal resolve the nota from the chave.
        body.set("dtEmissaoNFCe", "");
        // Cloudflare Turnstile injects this field into the form; the SE server reads it.
        body.set("cf-turnstile-response", turnstileToken);
        return httpPostConsult(CONSULT_POST_URL, body, cookieHeader).getBody();
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    /**
     * Resolves the 44-digit chave to the canonical consult-form URL — SE consults by
     * chave, not by the QR URL, so the fetch always targets the same portal page.
     */
    public String resolveConsultFormUrl() {
        return CONSULT_FORM_URL;
    }

    /**
     * The Turnstile sitekey, read from the page by its stable {@code 0x…} shape
     * (the SE page injects it via JS into {@code #rcaptcha}, so it is not a static
     * {@code data-sitekey} value), falling back to the configured default. Returns
     * null only when neither is available.
     */
    String extractSiteKey(String html) {
        var matcher = TURNSTILE_SITE_KEY.matcher(html == null ? "" : html);
        if (matcher.find()) {
            return matcher.group();
        }
        return defaultSiteKey.isBlank() ? null : defaultSiteKey;
    }

    /**
     * True when the portal answered with the consult form's error panel instead of a
     * DANFE — "Captcha inválido." / "Erro na digitação dos dados." — meaning the
     * Turnstile token was rejected and a fresh one must be solved. Checked on
     * accent-free prefixes so the ISO-8859-1 page decodes robustly.
     */
    static boolean isCaptchaRejected(String html) {
        if (html == null) return false;
        var lower = html.toLowerCase();
        return lower.contains("captcha inv") || lower.contains("erro na digita");
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(UnidadeFederativa.SE.name());
        }
    }

    // ── HTTP seams (overridable in tests) ──

    protected ResponseEntity<String> httpGetForm(String url) {
        return restClient.get().uri(URI.create(url)).retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> httpPostConsult(String url, MultiValueMap<String, String> body, String cookieHeader) {
        return restClient.post()
                .uri(URI.create(url))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                    headers.set("Origin", "https://nfce.sefaz.se.gov.br");
                    headers.set("Referer", CONSULT_FORM_URL);
                })
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    /** First pair of each Set-Cookie, de-duplicated by name (last value wins). */
    private static String cookieHeader(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return null;
        var byName = new LinkedHashMap<String, String>();
        for (var cookie : setCookieHeaders) {
            var pair = cookie.split(";", 2)[0];
            var eq = pair.indexOf('=');
            if (eq > 0) {
                byName.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        if (byName.isEmpty()) return null;
        return byName.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    @Override
    public Optional<String> preflightChave(String qrPayload) {
        // SE QR payloads carry the chave in the ?p= param, which
        // ChaveAcessoParser.extractChave already resolves — no preflight needed.
        return Optional.empty();
    }
}
