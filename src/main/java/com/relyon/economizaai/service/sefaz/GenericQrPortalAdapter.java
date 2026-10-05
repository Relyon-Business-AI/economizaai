package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ExperimentalCaptchaWallException;
import com.relyon.economizaai.exception.ExperimentalPortalFetchException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Experimental catch-all adapter for states WITHOUT a verified adapter. Every
 * NFC-e QR code carries the full URL of the emitting state's consult portal,
 * and the majority of portals render the same responsive-DANFE layout the
 * shared parser already understands (the pattern proven on RS, PR, SP and MS —
 * see docs/MULTI_STATE_RECON.md: ~10 UFs are plain server-rendered GETs). So
 * the most likely way to support an unknown state is simply: fetch the QR's
 * own URL and run {@link ResponsiveDanfeParser} on it.
 *
 * <p>{@link SefazIngestionService} assigns this adapter to every UF no
 * dedicated adapter claims (gap-fill, never competing with a verified one) and
 * orchestrates the rest of the chain: Infosimples rescue on fetch/parse
 * failure, per-layer telemetry ({@code state_ingestion_attempts}), and the
 * admin alert when every layer fails. Kill-switch:
 * {@code SEFAZ_EXPERIMENTAL_ENABLED}.
 *
 * <p>Captcha walls get ONE solve (reCAPTCHA v2 / Turnstile) whose token is then
 * replayed TWO generic ways, best-effort, with that single token (no extra solve):
 * (1) replaying the captcha page's own {@code <form>} as a POST with the token in
 * both common field names + every hidden input preserved (the shape DF/MG/SC use),
 * and (2) resubmitting the token as a URL query param. The first that returns a
 * non-captcha page wins. If both fail, the failure carries the captcha type +
 * sitekey + page snippet as evidence for building a dedicated adapter, and
 * Infosimples rescues the receipt.
 *
 * <p>SSRF guard: only hosts under the configured suffixes (default
 * {@code gov.br} — every SEFAZ portal lives there) are fetched, so a crafted
 * QR can't point the server at internal or arbitrary hosts.
 */
@Slf4j
@Component
public class GenericQrPortalAdapter implements SefazAdapter {

    private static final Pattern CAPTCHA_MARKER = Pattern.compile(
            "g-recaptcha|recaptcha/api\\.js|hcaptcha|cf-turnstile|challenge-form", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_KEY = Pattern.compile("data-sitekey=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    // A Google reCAPTCHA v2 site key is exactly 40 chars of [A-Za-z0-9_-]. MG's JSF
    // portal carries a decoy/placeholder data-sitekey before the real one; sending a
    // malformed key to the solver is a guaranteed paid-call rejection ("invalid
    // websiteKey, its length should be 40"), so we validate the shape before solving.
    private static final Pattern RECAPTCHA_SITE_KEY = Pattern.compile("[A-Za-z0-9_-]{40}");
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final int EVIDENCE_SNIPPET_CHARS = 600;
    // The JDK HttpURLConnection auto-follows same-scheme redirects but NOT
    // http->https (PE bounces http:80 -> https:444 and answers with the NFe XML).
    // We follow those hops ourselves, re-checking the SSRF allowlist each time.
    private static final int MAX_REDIRECTS = 4;

    private final RestClient restClient;
    private final CaptchaSolver captchaSolver;
    private final boolean enabled;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final Set<String> allowedHostSuffixes;

    public GenericQrPortalAdapter(RestClient.Builder builder,
                                  CaptchaSolver captchaSolver,
                                  @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent,
                                  @Value("${economizaai.ingestion.sefaz.experimental.enabled:true}") boolean enabled,
                                  @Value("${economizaai.ingestion.sefaz.experimental.max-attempts:3}") int maxAttempts,
                                  @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                  @Value("${economizaai.ingestion.sefaz.experimental.allowed-host-suffixes:gov.br}") String allowedHostSuffixes) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        this.captchaSolver = captchaSolver;
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.allowedHostSuffixes = parseSuffixes(allowedHostSuffixes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Claims nothing directly — {@link SefazIngestionService} gap-fills it into
     * every UF left unclaimed by the dedicated adapters.
     */
    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.noneOf(UnidadeFederativa.class);
    }

    /** A bare chave has no portal URL to fetch — route it to the by-chave fallback. */
    @Override
    public boolean requiresQrSignature() {
        return true;
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        var uf = ChaveAcessoParser.extractUf(ChaveAcessoParser.extractChave(qrPayload));
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt, uf);
            } catch (TransientFetchException ex) {
                if (attempt >= maxAttempts) break;
                log.warn("sefaz.experimental.retry uf={} attempt={}/{} reason={}", uf, attempt, maxAttempts, ex.getMessage());
                sleep(retryDelayMs, uf);
            }
        }
        log.warn("sefaz.experimental.exhausted uf={} attempts={} url={}", uf, maxAttempts, url);
        throw new ExperimentalPortalFetchException(uf.name(),
                "transient failures exhausted " + maxAttempts + " attempts (timeouts/5xx/empty body)");
    }

    private String fetchOnce(String url, int attempt, UnidadeFederativa uf) {
        log.info("sefaz.experimental.fetch uf={} attempt={}/{} url={}", uf, attempt, maxAttempts, url);
        try {
            var fetched = httpFetch(url);
            if (fetched.body() == null || fetched.body().isBlank()) {
                throw new TransientFetchException("empty-body");
            }
            if (CAPTCHA_MARKER.matcher(fetched.body()).find()) {
                return handleCaptchaWall(fetched, uf);
            }
            log.info("sefaz.experimental.fetch.ok uf={} bytes={}", uf, fetched.body().length());
            return fetched.body();
        } catch (HttpClientErrorException ex) {
            // 4xx — the portal exists but rejects this consult shape. Deterministic
            // for THIS layer; the chain may still rescue via Infosimples.
            log.warn("sefaz.experimental.client_error uf={} status={}", uf, ex.getStatusCode());
            throw new ExperimentalPortalFetchException(uf.name(),
                    "portal returned " + ex.getStatusCode() + "\nresponse body (first chars):\n"
                            + snippet(ex.getResponseBodyAsString()));
        } catch (RestClientException ex) {
            throw new TransientFetchException(ex.getClass().getSimpleName());
        }
    }

    /**
     * Best-effort captcha on an unknown portal: ONE solve, then the token is replayed
     * two generic ways with no extra solve — the page's own form as a POST first (the
     * common shape), then as a URL query param. The first non-captcha response wins;
     * if both are rejected the caller falls through to the rescuable wall signal.
     */
    private String handleCaptchaWall(PortalFetch fetched, UnidadeFederativa uf) {
        var captchaPageHtml = fetched.body();
        var captchaType = detectCaptchaType(captchaPageHtml);
        var siteKey = extractSiteKey(captchaPageHtml, captchaType);
        if (captchaSolver.isConfigured() && siteKey != null && captchaType.solvable()) {
            try {
                log.info("sefaz.experimental.captcha_solving uf={} type={}", uf, captchaType);
                var token = captchaType == CaptchaType.RECAPTCHA_V2
                        ? captchaSolver.solveRecaptchaV2(siteKey, fetched.finalUrl())
                        : captchaSolver.solveCloudflareTurnstile(siteKey, fetched.finalUrl());

                // 1) Replay the captcha page's own <form> as a POST (DF/MG/SC shape).
                var viaForm = tryFormReplay(fetched, token, uf);
                if (isDanfe(viaForm)) {
                    log.info("sefaz.experimental.captcha_passed uf={} type={} via=form_post", uf, captchaType);
                    return viaForm;
                }
                // 2) Fallback: resubmit the token as a URL query param (older guess).
                var separator = fetched.finalUrl().contains("?") ? "&" : "?";
                var viaQuery = httpFetch(fetched.finalUrl() + separator + captchaType.responseParam() + "=" + token).body();
                if (isDanfe(viaQuery)) {
                    log.info("sefaz.experimental.captcha_passed uf={} type={} via=query_param", uf, captchaType);
                    return viaQuery;
                }
                log.info("sefaz.experimental.captcha_resubmit_rejected uf={} type={}", uf, captchaType);
            } catch (RuntimeException solveEx) {
                log.warn("sefaz.experimental.captcha_solve_failed uf={} type={} {}",
                        uf, captchaType, solveEx.getMessage());
            }
        } else {
            log.info("sefaz.experimental.captcha_wall uf={} type={} solverConfigured={} siteKeyFound={}",
                    uf, captchaType, captchaSolver.isConfigured(), siteKey != null);
        }
        throw new ExperimentalCaptchaWallException(uf.name(), captchaType.name(), siteKey,
                snippet(captchaPageHtml));
    }

    /**
     * Replays the captcha page's own {@code <form>} as a POST: every hidden input is
     * preserved and the solved token is set under BOTH common field names
     * ({@code cf-turnstile-response} + {@code g-recaptcha-response}) — Turnstile often
     * runs in reCAPTCHA-compat mode, so the field name doesn't match the widget type.
     * Returns null when there's no form or the POST didn't return a DANFE, so the
     * caller falls through to the query-param guess.
     */
    private String tryFormReplay(PortalFetch fetched, String token, UnidadeFederativa uf) {
        var document = Jsoup.parse(fetched.body(), fetched.finalUrl());
        var form = document.selectFirst("form");
        if (form == null) {
            return null;
        }
        String action;
        try {
            var rawAction = form.hasAttr("action") && !form.attr("action").isBlank()
                    ? form.absUrl("action") : fetched.finalUrl();
            action = resolveUrl(rawAction); // SSRF re-check on the POST target
        } catch (InvalidQrPayloadException rejected) {
            return null;
        }
        var body = new LinkedMultiValueMap<String, String>();
        for (var input : form.select("input[name]")) {
            body.add(input.attr("name"), input.attr("value"));
        }
        body.set("cf-turnstile-response", token);
        body.set("g-recaptcha-response", token);
        try {
            log.info("sefaz.experimental.form_replay uf={} action={}", uf, action);
            return httpPostForm(action, body, fetched.cookieHeader());
        } catch (RestClientException ex) {
            log.warn("sefaz.experimental.form_replay_failed uf={} reason={}", uf, ex.getClass().getSimpleName());
            return null;
        }
    }

    private boolean isDanfe(String html) {
        return html != null && !html.isBlank() && !CAPTCHA_MARKER.matcher(html).find();
    }

    private enum CaptchaType {
        RECAPTCHA_V2("g-recaptcha-response"),
        TURNSTILE("cf-turnstile-response"),
        HCAPTCHA(null),
        UNKNOWN_CHALLENGE(null);

        private final String responseParam;

        CaptchaType(String responseParam) {
            this.responseParam = responseParam;
        }

        boolean solvable() {
            return responseParam != null;
        }

        String responseParam() {
            return responseParam;
        }
    }

    private static CaptchaType detectCaptchaType(String html) {
        var lower = html.toLowerCase();
        // Check Turnstile FIRST: it can run in reCAPTCHA-compat mode (rendered into a
        // .g-recaptcha div with a 0x… key), so the g-recaptcha class alone would
        // misclassify it as reCAPTCHA (DF does exactly this).
        if (lower.contains("cf-turnstile") || lower.contains("challenges.cloudflare.com/turnstile")) {
            return CaptchaType.TURNSTILE;
        }
        if (lower.contains("g-recaptcha") || lower.contains("recaptcha/api.js")) return CaptchaType.RECAPTCHA_V2;
        if (lower.contains("hcaptcha")) return CaptchaType.HCAPTCHA;
        return CaptchaType.UNKNOWN_CHALLENGE;
    }

    /**
     * Returns the first {@code data-sitekey} on the page that is well-formed for the
     * detected captcha type. A page may carry a decoy/placeholder key before the real
     * one (MG's portal does), so we scan every match instead of blindly taking the
     * first. For reCAPTCHA v2 an unusable key yields {@code null} so the caller skips
     * the solve entirely — a malformed key is a guaranteed paid-call rejection. For
     * types we can't validate confidently we fall back to the first key seen.
     */
    private static String extractSiteKey(String html, CaptchaType captchaType) {
        var matcher = SITE_KEY.matcher(html);
        String firstSeen = null;
        while (matcher.find()) {
            var candidate = matcher.group(1).trim();
            if (candidate.isBlank()) continue;
            if (firstSeen == null) firstSeen = candidate;
            if (isWellFormedSiteKey(candidate, captchaType)) return candidate;
        }
        return captchaType == CaptchaType.RECAPTCHA_V2 ? null : firstSeen;
    }

    private static boolean isWellFormedSiteKey(String candidate, CaptchaType captchaType) {
        return switch (captchaType) {
            case RECAPTCHA_V2 -> RECAPTCHA_SITE_KEY.matcher(candidate).matches();
            case TURNSTILE -> candidate.startsWith("0x");
            default -> true;
        };
    }

    private static String snippet(String body) {
        if (body == null) return "";
        var sanitized = CpfMasker.strip(body);
        return sanitized.length() <= EVIDENCE_SNIPPET_CHARS
                ? sanitized
                : sanitized.substring(0, EVIDENCE_SNIPPET_CHARS);
    }

    /**
     * Raw HTTP GET that follows cross-scheme redirects the JDK client won't (see
     * {@link #MAX_REDIRECTS}), returning the body + the FINAL url + accumulated
     * cookies — the form replay needs the post-redirect url and session cookies.
     * Isolated as a seam so the retry loop is unit-testable. Every redirect hop is
     * re-validated against the SSRF allowlist via {@link #resolveUrl}, so a portal
     * can't bounce the server off {@code gov.br}.
     */
    protected PortalFetch httpFetch(String url) {
        var current = url;
        var cookies = new LinkedHashMap<String, String>();
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var response = exchange(current);
            mergeCookies(cookies, response);
            if (!response.getStatusCode().is3xxRedirection()) {
                return new PortalFetch(response.getBody(), current, cookieHeader(cookies));
            }
            var location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null || location.isBlank()) {
                return new PortalFetch(response.getBody(), current, cookieHeader(cookies));
            }
            current = resolveUrl(location);
        }
        throw new TransientFetchException("too-many-redirects");
    }

    /** Body-only convenience over {@link #httpFetch}. */
    protected String httpGet(String url) {
        return httpFetch(url).body();
    }

    /** Single HTTP GET returning status + headers + body — the seam the redirect loop (and tests) build on. */
    protected ResponseEntity<String> exchange(String url) {
        return restClient.get().uri(url).retrieve().toEntity(String.class);
    }

    /** Form-urlencoded POST (the captcha form replay), carrying the captcha page's cookies. */
    protected String httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                })
                .body(body)
                .retrieve()
                .toEntity(String.class)
                .getBody();
    }

    @Override
    public ParsedReceipt parseHtml(String body, String chaveAcesso, String sourceUrl) {
        if (NfceXmlParser.looksLikeNfeXml(body)) {
            return NfceXmlParser.parse(body, chaveAcesso, sourceUrl);
        }
        return ResponsiveDanfeParser.parse(body, chaveAcesso, sourceUrl);
    }

    /**
     * Only full URLs on an allowed host suffix are fetched. Bare chaves never
     * reach here ({@link #requiresQrSignature()} routes them to the fallback),
     * and a QR pointing outside gov.br is treated as invalid, not fetched.
     */
    String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (!trimmed.toLowerCase().startsWith("http")) {
            throw new InvalidQrPayloadException();
        }
        var matcher = URL_HOST.matcher(trimmed);
        if (!matcher.find()) {
            throw new InvalidQrPayloadException();
        }
        var host = matcher.group(2).toLowerCase();
        var allowed = allowedHostSuffixes.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!allowed) {
            log.warn("sefaz.experimental.url.rejected host not under allowed suffixes");
            throw new InvalidQrPayloadException();
        }
        return trimmed;
    }

    private static void mergeCookies(Map<String, String> jar, ResponseEntity<String> response) {
        var setCookies = response.getHeaders().get("Set-Cookie");
        if (setCookies == null) return;
        for (var cookie : setCookies) {
            var pair = cookie.split(";", 2)[0];
            var eq = pair.indexOf('=');
            if (eq > 0) {
                jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        if (jar.isEmpty()) return null;
        return jar.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private void sleep(long ms, UnidadeFederativa uf) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(uf.name());
        }
    }

    private Set<String> parseSuffixes(String csv) {
        var suffixes = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim)
                .filter(suffix -> !suffix.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
        return suffixes.isEmpty() ? Set.of("gov.br") : suffixes;
    }

    /** A portal page: body + the url after following redirects + accumulated cookies. */
    protected record PortalFetch(String body, String finalUrl, String cookieHeader) {
    }

    private static class TransientFetchException extends RuntimeException {
        TransientFetchException(String reason) {
            super(reason);
        }
    }
}
