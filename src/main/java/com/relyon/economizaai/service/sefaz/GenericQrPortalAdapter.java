package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ExperimentalCaptchaWallException;
import com.relyon.economizaai.exception.ExperimentalPortalFetchException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.EnumSet;
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
 * <p>Captcha walls get ONE best-effort solve (reCAPTCHA v2 / Turnstile, token
 * resubmitted as a query param — a guess). If the portal rejects it, the
 * failure carries the captcha type + sitekey + page snippet as evidence for
 * building the dedicated adapter, and Infosimples rescues the receipt.
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
            var html = httpGet(url);
            if (html == null || html.isBlank()) {
                throw new TransientFetchException("empty-body");
            }
            if (CAPTCHA_MARKER.matcher(html).find()) {
                return handleCaptchaWall(html, url, uf);
            }
            log.info("sefaz.experimental.fetch.ok uf={} bytes={}", uf, html.length());
            return html;
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
     * Best-effort captcha attempt on an unknown portal: ONE solve (charged per
     * token, ~R$0.03, even when the portal then rejects it) resubmitted as a
     * query param — a guess, since only a dedicated adapter knows the portal's
     * real form shape. Any failure falls through to the captcha-wall signal,
     * which the chain rescues via Infosimples. Deliberately NOT the verified
     * captcha adapters' re-solve retry loop: with an unverified resubmit shape,
     * repeated solves would just burn money on a likely-wrong guess.
     */
    private String handleCaptchaWall(String captchaPageHtml, String url, UnidadeFederativa uf) {
        var captchaType = detectCaptchaType(captchaPageHtml);
        var siteKey = extractSiteKey(captchaPageHtml, captchaType);
        if (captchaSolver.isConfigured() && siteKey != null && captchaType.solvable()) {
            try {
                log.info("sefaz.experimental.captcha_solving uf={} type={}", uf, captchaType);
                var token = captchaType == CaptchaType.RECAPTCHA_V2
                        ? captchaSolver.solveRecaptchaV2(siteKey, url)
                        : captchaSolver.solveCloudflareTurnstile(siteKey, url);
                var separator = url.contains("?") ? "&" : "?";
                var resubmitted = httpGet(url + separator + captchaType.responseParam() + "=" + token);
                if (resubmitted != null && !resubmitted.isBlank() && !CAPTCHA_MARKER.matcher(resubmitted).find()) {
                    log.info("sefaz.experimental.captcha_passed uf={} type={}", uf, captchaType);
                    return resubmitted;
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
        if (lower.contains("g-recaptcha") || lower.contains("recaptcha/api.js")) return CaptchaType.RECAPTCHA_V2;
        if (lower.contains("cf-turnstile")) return CaptchaType.TURNSTILE;
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
     * {@link #MAX_REDIRECTS}). Isolated as a seam so the retry loop is unit-testable.
     * Every redirect hop is re-validated against the SSRF allowlist via
     * {@link #resolveUrl}, so a portal can't bounce the server off {@code gov.br}.
     */
    protected String httpGet(String url) {
        var current = url;
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var response = exchange(current);
            if (!response.getStatusCode().is3xxRedirection()) {
                return response.getBody();
            }
            var location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null || location.isBlank()) {
                return response.getBody();
            }
            current = resolveUrl(location);
        }
        throw new TransientFetchException("too-many-redirects");
    }

    /** Single HTTP GET returning status + headers + body — the seam the redirect loop (and tests) build on. */
    protected ResponseEntity<String> exchange(String url) {
        return restClient.get().uri(url).retrieve().toEntity(String.class);
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

    private static class TransientFetchException extends RuntimeException {
        TransientFetchException(String reason) {
            super(reason);
        }
    }
}
