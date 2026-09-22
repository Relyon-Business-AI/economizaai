package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SantaCatarinaNfcePortalAdapter implements SefazAdapter {

    private static final String BASE_URL = "https://sat.sef.sc.gov.br";
    private static final String CONSULT_URL = BASE_URL + "/tax.NET/Sat.DFe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx";
    private static final String TURNSTILE_FALLBACK_FIELD = "_ctl0:_ctl0:Body:Main:cf-turnstile-response";
    private static final String VALIDATE_EVENT_TARGET = "_ctl0$_ctl0$Body$Main$ButtonValidar";
    private static final Pattern URL_HOST = Pattern.compile(
            "^(https?)://([^/?#@\\\\]+?)(?::\\d+)?(?=[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAVE = Pattern.compile("\\d{44}");

    private final CaptchaSolver captchaSolver;
    private final RestClient restClient;
    private final RestClient noRedirectClient;
    private final int maxAttempts;
    private final long retryDelayMs;

    public SantaCatarinaNfcePortalAdapter(RestClient.Builder builder,
                                          CaptchaSolver captchaSolver,
                                          @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                          @Value("${economizaai.ingestion.sefaz.retry.sc-max-attempts:3}") int maxAttempts,
                                          @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                          @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
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
        var noRedirectFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        noRedirectFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        noRedirectFactory.setReadTimeout(timeoutMs);
        this.noRedirectClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .requestFactory(noRedirectFactory)
                .build();
        log.info("SantaCatarinaNfcePortalAdapter active retry maxAttempts={} delayMs={} captchaSolver configured={}",
                this.maxAttempts, this.retryDelayMs, captchaSolver.isConfigured());
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.SC);
    }

    @Override
    public Optional<String> preflightChave(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) return Optional.empty();
        try {
            var url = resolveUrl(qrPayload);
            if (!isAllowedScUrl(url)) return Optional.empty();
            var html = getFollowingRedirects(url).body();
            return extractChaveFromHtml(html);
        } catch (RuntimeException ex) {
            log.debug("sc.preflight.failed reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Fetches the DANFE, retrying a rejected Cloudflare Turnstile token (each
     * retry re-solves fresh) and transient portal errors up to {@code maxAttempts}.
     * The Turnstile solver is imperfect — a token is occasionally rejected — so
     * without this a valid SC receipt would fail on the first bad token, forcing
     * a needless rescan. Deterministic failures (no solver, bad URL, sitekey
     * missing) propagate immediately. Mirrors {@link MsDfePortalAdapter}.
     */
    @Override
    public String fetchHtml(String qrPayload) {
        var url = resolveUrl(qrPayload);
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(url, attempt);
            } catch (CaptchaSolveFailedException | RestClientException ex) {
                // Turnstile rejected (bad token) or transient network/5xx — re-solve and retry.
                if (attempt >= maxAttempts) break;
                var delay = attempt == 1 ? 0 : retryDelayMs;
                log.warn("sc.fetch.retry attempt={}/{} reason={} nextDelayMs={}",
                        attempt, maxAttempts, ex.getClass().getSimpleName(), delay);
                sleep(delay);
            }
            // CaptchaUnavailableException / ReceiptParseException / InvalidQrPayloadException
            // are deterministic — they propagate without being caught here.
        }
        log.warn("sc.fetch.exhausted attempts={}", maxAttempts);
        throw new SefazFetchException(UnidadeFederativa.SC.name());
    }

    private String fetchOnce(String url, int attempt) {
        log.info("sc.fetch attempt={}/{}", attempt, maxAttempts);
        var page = getFollowingRedirects(url);
        var html = page.body();
        if (html == null || html.isBlank()) {
            // Transient empty body — let the retry loop handle it.
            throw new RestClientException("sc-empty-response-body");
        }
        var danfe = ScSecurityChallengeDetector.looksLikeHtml(html)
                ? solveSecurityChallenge(html, url, page.cookieHeader())
                : html;
        if (!ScNfceDanfeParser.hasItems(danfe)) {
            // Neither the challenge nor a valid DANFE — a flaky error/interstitial.
            // Re-fetch instead of returning a page the parser would reject as 0-item.
            log.warn("sc.fetch.not_danfe attempt={}/{} — re-fetching", attempt, maxAttempts);
            throw new RestClientException("sc-not-a-danfe");
        }
        return danfe;
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(UnidadeFederativa.SC.name());
        }
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ScNfceDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    public String resolveUrl(String qrPayload) {
        var trimmed = qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("http")) {
            if (!isAllowedScUrl(trimmed)) {
                log.warn("sc.url.rejected host not in allowlist");
                throw new InvalidQrPayloadException();
            }
            return encodeScQuerySeparators(trimmed);
        }
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        return CONSULT_URL + "?p=" + chave + "%7C3%7C1";
    }

    private String solveSecurityChallenge(String securityHtml, String currentUrl, String cookieHeader) {
        if (!captchaSolver.isConfigured()) {
            throw new CaptchaUnavailableException(UnidadeFederativa.SC.name());
        }
        var siteKey = extractTurnstileSiteKey(securityHtml);
        if (siteKey == null) {
            throw new ReceiptParseException("turnstile-sitekey-missing");
        }
        var securityUrl = securityUrl(securityHtml, currentUrl);
        var chave = extractChaveFromHtml(securityHtml).orElse(null);
        log.info("sc.turnstile.solving chave={} siteKey={}", LogMasker.chave(chave), siteKey);
        var token = captchaSolver.solveCloudflareTurnstile(siteKey, securityUrl);
        var postResponse = submitSecurityForm(securityHtml, securityUrl, token, cookieHeader);
        var danfe = followIfRedirect(postResponse, mergeCookies(cookieHeader, postResponse.getHeaders().get("Set-Cookie")));
        if (danfe == null || danfe.isBlank()) {
            throw new SefazFetchException(UnidadeFederativa.SC.name());
        }
        if (ScSecurityChallengeDetector.looksLikeHtml(danfe)) {
            throw new CaptchaSolveFailedException("turnstile-rejected");
        }
        return danfe;
    }

    static Optional<String> extractChaveFromHtml(String html) {
        if (html == null || html.isBlank()) return Optional.empty();
        var parsed = ScNfceDanfeParser.extractChave(Jsoup.parse(html));
        if (parsed.isPresent()) return parsed;

        var document = Jsoup.parse(html);
        for (var input : document.select("input[name=__VIEWSTATE]")) {
            var decoded = decodeBase64Text(input.attr("value"));
            if (decoded == null) continue;
            var urlDecoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            var matcher = CHAVE.matcher(urlDecoded);
            if (matcher.find()) return Optional.of(matcher.group());
        }
        return Optional.empty();
    }

    static String extractTurnstileSiteKey(String html) {
        var document = Jsoup.parse(html);
        var turnstile = document.selectFirst(".cf-turnstile[data-sitekey]");
        if (turnstile == null) return null;
        var siteKey = turnstile.attr("data-sitekey").trim();
        return siteKey.isBlank() ? null : siteKey;
    }

    // URLs are passed as pre-built URIs: the portal's rq token is a SIGNED,
    // already-percent-encoded blob, and RestClient.uri(String) treats strings as
    // URI templates and re-encodes them (%2F -> %252F), which the portal rejects
    // with a redirect to Errors/UrlTampering.aspx.
    protected ResponseEntity<String> httpGetResponse(String url) {
        return restClient.get().uri(URI.create(url)).retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> httpGetResponse(String url, String cookieHeader) {
        return noRedirectClient.get()
                .uri(URI.create(url))
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set("Cookie", cookieHeader);
                    }
                })
                .retrieve()
                .toEntity(String.class);
    }

    private PortalPage getFollowingRedirects(String url) {
        var currentUrl = url;
        String cookieHeader = null;
        for (var i = 0; i < 6; i++) {
            var response = httpGetResponse(currentUrl, cookieHeader);
            cookieHeader = mergeCookies(cookieHeader, response.getHeaders().get("Set-Cookie"));
            if (!response.getStatusCode().is3xxRedirection()) {
                return new PortalPage(response.getBody(), cookieHeader);
            }
            var location = response.getHeaders().getLocation();
            if (location == null) {
                throw new ReceiptParseException("sc-redirect-missing-location");
            }
            currentUrl = absoluteUrl(location.toString());
        }
        throw new ReceiptParseException("sc-redirect-loop");
    }

    protected ResponseEntity<String> submitSecurityForm(String securityHtml, String securityUrl,
                                                        String turnstileToken, String cookieHeader) {
        var document = Jsoup.parse(securityHtml, BASE_URL);
        var form = document.selectFirst("form");
        if (form == null) throw new ReceiptParseException("sc-security-form-missing");
        var action = form.hasAttr("action") ? absoluteUrl(form.attr("action")) : securityUrl;
        var body = new LinkedMultiValueMap<String, String>();
        for (var input : form.select("input[name]")) {
            body.add(input.attr("name"), input.attr("value"));
        }
        body.set("__EVENTTARGET", VALIDATE_EVENT_TARGET);
        body.set("__EVENTARGUMENT", "");
        body.set(turnstileFieldName(form), turnstileToken);
        body.set("cf-turnstile-response", turnstileToken);
        return httpPostForm(action, body, cookieHeader);
    }

    protected ResponseEntity<String> httpPostForm(String url, MultiValueMap<String, String> body, String cookieHeader) {
        return noRedirectClient.post()
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
                .toEntity(String.class);
    }

    private String followIfRedirect(ResponseEntity<String> response, String cookieHeader) {
        if (!response.getStatusCode().is3xxRedirection()) {
            return response.getBody();
        }
        var location = response.getHeaders().getLocation();
        if (location == null) throw new ReceiptParseException("sc-security-redirect-missing-location");
        var first = httpGetResponse(absoluteUrl(location.toString()), cookieHeader);
        if (!first.getStatusCode().is3xxRedirection()) {
            return first.getBody();
        }
        var next = first.getHeaders().getLocation();
        if (next == null) throw new ReceiptParseException("sc-security-final-redirect-missing-location");
        return httpGetResponse(absoluteUrl(next.toString()), cookieHeader).getBody();
    }

    private String securityUrl(String securityHtml, String fallback) {
        var form = Jsoup.parse(securityHtml, BASE_URL).selectFirst("form");
        if (form == null || !form.hasAttr("action")) return fallback;
        return absoluteUrl(form.attr("action"));
    }

    private static String turnstileFieldName(org.jsoup.nodes.Element form) {
        var input = form.selectFirst("input[name$=cf-turnstile-response]");
        return input == null ? TURNSTILE_FALLBACK_FIELD : input.attr("name");
    }

    private static String decodeBase64Text(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var padded = value + "=".repeat((4 - value.length() % 4) % 4);
            return new String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isAllowedScUrl(String url) {
        var matcher = URL_HOST.matcher(url);
        if (!matcher.find()) return false;
        var host = matcher.group(2).toLowerCase();
        return host.equals("sef.sc.gov.br")
                || host.endsWith(".sef.sc.gov.br");
    }

    private static String absoluteUrl(String value) {
        if (value == null || value.isBlank()) return BASE_URL;
        if (value.toLowerCase().startsWith("http")) return value;
        return value.startsWith("/") ? BASE_URL + value : BASE_URL + "/" + value;
    }

    private static String encodeScQuerySeparators(String value) {
        return value.replace("|", "%7C");
    }

    /**
     * Merges cookies across redirect hops, keyed by NAME so a re-set cookie
     * (e.g. a fresh ASP.NET_SessionId on a later hop) REPLACES the stale value
     * instead of the header carrying {@code NAME=old; NAME=new} — per RFC 6265
     * the server may pick the first (stale) one, invalidating the session.
     */
    private static String mergeCookies(String existingCookieHeader, List<String> setCookieHeaders) {
        var byName = new java.util.LinkedHashMap<String, String>();
        putCookie(byName, existingCookieHeader == null ? null : existingCookieHeader.split(";\\s*"));
        if (setCookieHeaders != null) {
            setCookieHeaders.stream().map(cookie -> cookie.split(";")[0]).forEach(pair -> putCookie(byName, pair));
        }
        if (byName.isEmpty()) return null;
        return byName.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private static void putCookie(java.util.LinkedHashMap<String, String> byName, String... pairs) {
        if (pairs == null) return;
        for (var pair : pairs) {
            if (pair == null || pair.isBlank() || !pair.contains("=")) continue;
            var eq = pair.indexOf('=');
            byName.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
    }

    private static void putCookie(java.util.LinkedHashMap<String, String> byName, String pair) {
        putCookie(byName, new String[]{pair});
    }

    private record PortalPage(String body, String cookieHeader) {
    }
}
