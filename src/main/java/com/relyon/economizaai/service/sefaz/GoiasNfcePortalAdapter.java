package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goiás NFC-e portal (nfeweb.sefaz.go.gov.br). No captcha, but a two-step
 * dance (verified with a real organic scan, 2026-07-22 — see
 * {@code fixtures/sefaz/go/}):
 *
 * <ol>
 *   <li>GET the public consult shell ({@code danfeNFCe?p=<chave>|3|1}) — its
 *       only job is to mint a JSESSIONID; the DANFE itself sits in an iframe.</li>
 *   <li>GET the iframe ({@code render/danfeNFCe?chNFe=<chave>}) WITH that
 *       session cookie. The response embeds the complete pre-rendered DANFE as
 *       an escaped JS string inside {@code new DanfeNFCe('…','…','<html>')} —
 *       without the cookie the third argument comes back {@code null}.</li>
 * </ol>
 *
 * <p>The extracted markup is the standard national {@code tabResult} layout,
 * so parsing is delegated to {@link ScNfceDanfeParser} (state-agnostic
 * text-pattern parser despite the name). The QR consult takes a bare chave
 * (no signature params), so manual chave entry works for GO too.
 *
 * <p>Retry classification per CLAUDE.md: transient (HTTP failure, missing
 * session → null/absent embed) retries up to {@code max-attempts};
 * deterministic (no chave in payload) propagates immediately.
 */
@Slf4j
@Component
public class GoiasNfcePortalAdapter implements SefazAdapter {

    private static final String BASE_URL = "https://nfeweb.sefaz.go.gov.br";
    private static final String SHELL_URL = BASE_URL + "/nfeweb/sites/nfce/danfeNFCe?p=";
    private static final String DANFE_CALL_MARKER = "new DanfeNFCe(";
    private static final Pattern JSESSIONID_IN_URL = Pattern.compile(";jsessionid=([A-Za-z0-9._\\-:]+)");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryDelayMs;

    public GoiasNfcePortalAdapter(RestClient.Builder builder,
                                  @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizaai.ingestion.sefaz.retry.max-attempts:5}") int maxAttempts,
                                  @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                  @Value("${economizaai.ingestion.sefaz.go-user-agent:Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36}") String userAgent) {
        // Browser-like UA on purpose: the portal sits behind F5 bot defense
        // (TS* cookies) that intermittently serves cloud IPs a null DANFE embed
        // for non-browser UAs — the same request from a residential IP works.
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
        log.info("GoiasNfcePortalAdapter active retry maxAttempts={} delayMs={}", this.maxAttempts, this.retryDelayMs);
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.GO);
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        var shellUrl = shellUrl(qrPayload, chave);
        RuntimeException lastTransient = null;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(shellUrl, chave);
            } catch (SefazFetchException | RestClientException ex) {
                lastTransient = ex;
                log.warn("go.fetch transient attempt={}/{} chave={} reason={}: {}",
                        attempt, maxAttempts, LogMasker.chave(chave),
                        ex.getClass().getSimpleName(), abbreviate(ex.getMessage()));
                sleepBetweenAttempts(attempt);
            }
        }
        throw lastTransient instanceof SefazFetchException fetchFailure
                ? fetchFailure
                : new SefazFetchException(UnidadeFederativa.GO.name());
    }

    /**
     * GO issues TWO QR shapes: version 3 ({@code chave|3|1}, unsigned) and
     * version 2 ({@code chave|2|1|1|<hash>}, SIGNED). A signed consult only
     * answers with the DANFE when the ORIGINAL params — hash included — are
     * presented, so a scanned QR URL must be used verbatim; rebuilding
     * {@code |3|1} silently gets the empty shell (the exact bug that sent
     * every v2 nota to the paid fallback). Reconstruction is only for
     * manually-typed bare chaves, which v3 accepts.
     */
    static String shellUrl(String qrPayload, String chave) {
        var trimmed = qrPayload == null ? "" : qrPayload.trim();
        if (trimmed.toLowerCase().startsWith("https://") && trimmed.contains(".sefaz.go.gov.br/")) {
            return trimmed;
        }
        return SHELL_URL + chave + "|3|1";
    }

    private String fetchOnce(String shellUrl, String chave) {
        var shellResponse = restClient.get()
                .uri(shellUrl)
                .retrieve()
                .toEntity(String.class);
        var sessionCookies = sessionCookies(shellResponse);
        if (sessionCookies == null) {
            throw new SefazFetchException(UnidadeFederativa.GO.name());
        }
        // Belt and suspenders: besides the cookies, pin the session via JBoss
        // URL rewriting (the portal's own script tags use ;jsessionid=…), so a
        // route that drops cookies still lands on the session's node.
        var renderPage = restClient.get()
                .uri(renderUrl(chave, extractJsessionId(shellResponse.getBody())))
                .header(HttpHeaders.COOKIE, sessionCookies)
                .header(HttpHeaders.REFERER, shellUrl)
                .retrieve()
                .body(String.class);
        var danfeHtml = extractEmbeddedDanfe(renderPage);
        if (danfeHtml == null || danfeHtml.isBlank()) {
            // Session not honored (or portal hiccup) — the embed comes back null.
            // Transient: a fresh shell GET mints a new session on the next attempt.
            throw new SefazFetchException(UnidadeFederativa.GO.name());
        }
        log.info("go.fetch ok chave={} danfeChars={}", LogMasker.chave(chave), danfeHtml.length());
        return danfeHtml;
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ScNfceDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    static String renderUrl(String chave, String jsessionId) {
        var base = BASE_URL + "/nfeweb/sites/nfce/render/danfeNFCe";
        var sessionPath = jsessionId == null ? "" : ";jsessionid=" + jsessionId;
        return base + sessionPath + "?chNFe=" + chave;
    }

    /** The session id as the portal's own URL-rewritten links carry it (e.g. {@code abc.jbprodeap17:eap08}). */
    static String extractJsessionId(String shellHtml) {
        if (shellHtml == null) return null;
        var matcher = JSESSIONID_IN_URL.matcher(shellHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * ALL cookies from the shell response, not just JSESSIONID: the portal is a
     * load-balanced JBoss cluster with node-pinned sessions, and the LB routes
     * by its own cookies (CookieGenericoGoias + F5 TS*). Forwarding only the
     * JSESSIONID sent the render request to a random node, which answered with
     * a null embed — the intermittent failures the telemetry showed.
     */
    private static String sessionCookies(ResponseEntity<String> response) {
        var setCookies = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        if (setCookies.stream().noneMatch(cookie -> cookie.toUpperCase().startsWith("JSESSIONID"))) {
            return null;
        }
        return setCookies.stream()
                .map(cookie -> cookie.split(";", 2)[0].trim())
                .collect(Collectors.joining("; "));
    }

    /**
     * Pulls the third {@code new DanfeNFCe(...)} argument (the pre-rendered
     * DANFE) out of the render page and undoes the JS string escaping. Package
     * private for the fixture test. Returns null when the page has no embed
     * (e.g. sessionless request — the portal passes {@code null} instead).
     */
    static String extractEmbeddedDanfe(String renderPageHtml) {
        if (renderPageHtml == null) return null;
        var callStart = renderPageHtml.indexOf(DANFE_CALL_MARKER);
        if (callStart < 0) return null;
        // Walk the first two quoted args, then the third is either a quoted
        // string (the DANFE) or the literal `null` (sessionless request).
        // A character scan (not regex) — the embed is kilobytes long and a
        // quantified alternation overflows Java's regex recursion.
        var cursor = callStart + DANFE_CALL_MARKER.length();
        for (var argument = 1; argument <= 2; argument++) {
            var argStart = renderPageHtml.indexOf('\'', cursor);
            if (argStart < 0) return null;
            var argEnd = scanQuotedString(renderPageHtml, argStart);
            if (argEnd < 0) return null;
            cursor = argEnd + 1;
        }
        while (cursor < renderPageHtml.length()
                && (Character.isWhitespace(renderPageHtml.charAt(cursor)) || renderPageHtml.charAt(cursor) == ',')) {
            cursor++;
        }
        if (cursor >= renderPageHtml.length() || renderPageHtml.charAt(cursor) != '\'') {
            return null; // third argument is `null` — no DANFE in this response
        }
        var danfeEnd = scanQuotedString(renderPageHtml, cursor);
        if (danfeEnd < 0) return null;
        return unescapeJsString(renderPageHtml.substring(cursor + 1, danfeEnd));
    }

    /** Given the index of an opening single quote, returns the index of its closing quote (backslash-escape aware), or -1. */
    private static int scanQuotedString(String text, int openingQuote) {
        var index = openingQuote + 1;
        while (index < text.length()) {
            var current = text.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (current == '\'') return index;
            index++;
        }
        return -1;
    }

    private static String unescapeJsString(String value) {
        var unicodeDecoded = UNICODE_ESCAPE.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1), 16))));
        var result = new StringBuilder(unicodeDecoded.length());
        for (var index = 0; index < unicodeDecoded.length(); index++) {
            var current = unicodeDecoded.charAt(index);
            if (current == '\\' && index + 1 < unicodeDecoded.length()) {
                var next = unicodeDecoded.charAt(++index);
                result.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next; // \" \' \/ \\ and any other escaped literal
                });
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String abbreviate(String message) {
        if (message == null) return "(no message — likely null embed / missing session)";
        return message.length() <= 90 ? message : message.substring(0, 90);
    }

    private void sleepBetweenAttempts(int attempt) {
        if (attempt >= maxAttempts || retryDelayMs == 0) return;
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(UnidadeFederativa.GO.name());
        }
    }
}
