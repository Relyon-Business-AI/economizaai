package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * NFC-e adapter for portals that render the shared <b>responsive DANFE</b>
 * layout. Originally built for the SVRS portal
 * ({@code https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce}, RS + the states
 * that delegate to SVRS), but other states' own portals serve the exact same
 * markup — PR ({@code www.fazenda.pr.gov.br/nfce/qrcode}) verified with a real
 * fixture on 2026-06-12, SP ({@code www.nfce.fazenda.sp.gov.br}, plain-GET
 * ASP.NET portal, no captcha) verified 2026-07-06. The set of UFs this adapter
 * claims is driven by config
 * ({@code economizaai.ingestion.sefaz.svrs.states}, default {@code RS,PR,SP}) so a
 * curator can opt-in additional states empirically — submit a real chave,
 * verify the parser extracts fields correctly, then add the UF to the env var
 * (and the portal host to {@code allowed-url-hosts}).
 *
 * <p>Real QR codes carry the full portal URL, so fetching usually needs no
 * URL-building; for bare-chave payloads {@link #resolveUrl} picks the portal
 * by the chave's UF. States whose portal renders a DIFFERENT layout (MG,
 * BA, PE, …) need a dedicated adapter.
 */
@Slf4j
@Component
public class SvrsSharedPortalAdapter implements SefazAdapter {

    private static final String PORTAL_URL = "https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce";
    private static final String PR_PORTAL_URL = "https://www.fazenda.pr.gov.br/nfce/qrcode";
    private static final Pattern CAPTCHA_MARKER = Pattern.compile("g-recaptcha|recaptcha/api\\.js", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_KEY = Pattern.compile("data-sitekey=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final Set<UnidadeFederativa> supportedStates;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final Set<String> allowedUrlHosts;

    public SvrsSharedPortalAdapter(RestClient.Builder builder,
                                   CaptchaSolver captchaSolver,
                                   @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                   @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent,
                                   @Value("${economizaai.ingestion.sefaz.svrs.states:RS,PR,SP}") String svrsStates,
                                   @Value("${economizaai.ingestion.sefaz.retry.max-attempts:5}") int maxAttempts,
                                   @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                   @Value("${economizaai.ingestion.sefaz.allowed-url-hosts:svrs.rs.gov.br,sefaz.rs.gov.br,fazenda.pr.gov.br,sef.sc.gov.br,fazenda.sp.gov.br}") String allowedUrlHosts) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(requestFactory)
                .build();
        this.captchaSolver = captchaSolver;
        this.supportedStates = parseStates(svrsStates);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.allowedUrlHosts = parseAllowedHosts(allowedUrlHosts);
        log.info("SvrsSharedPortalAdapter active for UFs: {} (retry maxAttempts={} delayMs={} allowedUrlHosts={} captchaSolver configured={})",
                this.supportedStates, this.maxAttempts, this.retryDelayMs, this.allowedUrlHosts, captchaSolver.isConfigured());
    }

    private Set<UnidadeFederativa> parseStates(String csv) {
        if (csv == null || csv.isBlank()) return EnumSet.of(UnidadeFederativa.RS);
        var result = EnumSet.noneOf(UnidadeFederativa.class);
        for (var token : csv.split(",")) {
            var trimmed = token.trim().toUpperCase();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(UnidadeFederativa.valueOf(trimmed));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown UF '{}' in svrs.states", trimmed);
            }
        }
        return result.isEmpty() ? EnumSet.of(UnidadeFederativa.RS) : result;
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return supportedStates;
    }

    /**
     * These portals render the DANFE only for a fully-signed QR URL; a bare,
     * manually-typed chave (no signature) yields no items. So manual entry must
     * be routed to the fallback (Infosimples) rather than scraped here.
     */
    @Override
    public boolean requiresQrSignature() {
        return true;
    }

    /**
     * Fetches the NFC-e HTML, retrying transient SEFAZ-side failures (5xx,
     * timeouts, connection errors, empty body) so a flaky portal doesn't
     * force the user to keep re-submitting. The first retry is immediate;
     * subsequent retries wait {@code retryDelayMs} between attempts, up to
     * {@code maxAttempts} total (default: 5 — one user-triggered + 4 retries
     * at 0s, 5s, 5s, 5s). Client errors (4xx) are permanent and never
     * retried. After exhaustion a {@link SefazFetchException} surfaces.
     */
    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        if (ScSecurityChallengeDetector.looksLikeUrl(url)) {
            throw new CaptchaUnavailableException(UnidadeFederativa.SC.name());
        }
        var uf = ChaveAcessoParser.extractUf(ChaveAcessoParser.extractChave(qrPayload));
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt, uf);
            } catch (TransientSefazFetchException ex) {
                if (attempt >= maxAttempts) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                log.warn("sefaz.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getMessage(), delay);
                sleep(delay, uf);
            }
        }
        log.warn("sefaz.fetch.exhausted attempts={} url={}", maxAttempts, url);
        throw new SefazFetchException(uf.name());
    }

    private String fetchOnce(String url, int attempt, UnidadeFederativa uf) {
        log.info("sefaz.fetch attempt={}/{} url={}", attempt, maxAttempts, url);
        try {
            var html = httpGet(url);
            if (html == null || html.isBlank()) {
                throw new TransientSefazFetchException("empty-body");
            }
            if (uf == UnidadeFederativa.SC && ScSecurityChallengeDetector.looksLikeHtml(html)) {
                throw new CaptchaUnavailableException(uf.name());
            }
            if (CAPTCHA_MARKER.matcher(html).find()) {
                return handleCaptcha(html, url, uf);
            }
            log.info("sefaz.fetch.ok bytes={} attempt={}", html.length(), attempt);
            return html;
        } catch (HttpClientErrorException ex) {
            // 4xx — deterministic (bad/unknown chave): retrying won't help.
            log.warn("sefaz.fetch.client_error status={} url={}", ex.getStatusCode(), url);
            throw new SefazFetchException(uf.name());
        } catch (RestClientException ex) {
            // 5xx, read/connect timeouts, IO errors — transient, worth retrying.
            throw new TransientSefazFetchException(ex.getClass().getSimpleName());
        }
    }

    /** Raw HTTP GET — isolated as a seam so the retry loop is unit-testable. */
    protected String httpGet(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    private String handleCaptcha(String captchaPageHtml, String url, UnidadeFederativa uf) {
        var ufName = uf.name();
        if (!captchaSolver.isConfigured()) {
            log.warn("sefaz.fetch.captcha_wall uf={} solver not configured", ufName);
            throw new CaptchaUnavailableException(ufName);
        }
        var siteKey = extractSiteKey(captchaPageHtml);
        if (siteKey == null) {
            log.warn("sefaz.fetch.captcha_no_sitekey uf={}", ufName);
            throw new ReceiptParseException("captcha-sitekey-missing");
        }
        log.info("sefaz.fetch.captcha_solving uf={}", ufName);
        var token = captchaSolver.solveRecaptchaV2(siteKey, url);
        return fetchWithToken(url, token);
    }

    static String extractSiteKey(String html) {
        var matcher = SITE_KEY.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Resubmits the request with the solved captcha token. The exact form shape
     * for SVRS portals is UNVERIFIED — they don't currently use captcha. Adjust
     * if a real captcha wall is observed: check the portal's form action and
     * field names, then update this method.
     */
    protected String fetchWithToken(String originalUrl, String token) {
        var separator = originalUrl.contains("?") ? "&" : "?";
        return restClient.get()
                .uri(originalUrl + separator + "g-recaptcha-response=" + token)
                .retrieve()
                .body(String.class);
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

    /** Marker for a retryable SEFAZ failure (kept internal to the adapter). */
    private static class TransientSefazFetchException extends RuntimeException {
        TransientSefazFetchException(String reason) {
            super(reason);
        }
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    /**
     * Resolves the QR payload into the URL we will fetch. Absolute URLs are
     * only accepted when their host matches the configured SEFAZ allowlist
     * (suffix match, e.g. {@code svrs.rs.gov.br} admits
     * {@code dfe-portal.svrs.rs.gov.br}) — anything else is rejected so a
     * crafted QR code can't point the server at internal or arbitrary hosts.
     * Bare-chave payloads (no URL — manual entry / reparse) build the consult
     * URL on the portal that serves the chave's UF.
     */
    public String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("http")) {
            if (!isAllowedSefazUrl(trimmed)) {
                log.warn("sefaz.url.rejected host not in allowlist");
                throw new InvalidQrPayloadException();
            }
            return trimmed;
        }
        return fallbackPortalFor(trimmed) + "?p=" + trimmed;
    }

    /** Portal that serves bare-chave consults for the payload's UF. */
    private String fallbackPortalFor(String chavePayload) {
        var uf = ChaveAcessoParser.extractUf(ChaveAcessoParser.extractChave(chavePayload));
        return uf == UnidadeFederativa.PR ? PR_PORTAL_URL : PORTAL_URL;
    }

    // Manual scheme/host extraction instead of URI.create: real NFC-e QR URLs
    // carry raw '|' separators in the query string, which java.net.URI rejects.
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);

    private boolean isAllowedSefazUrl(String url) {
        var matcher = URL_HOST.matcher(url);
        if (!matcher.find()) return false;
        var host = matcher.group(2).toLowerCase();
        return allowedUrlHosts.stream()
                .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }

    private Set<String> parseAllowedHosts(String csv) {
        var hosts = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
        return hosts.isEmpty() ? Set.of("svrs.rs.gov.br") : hosts;
    }
}
