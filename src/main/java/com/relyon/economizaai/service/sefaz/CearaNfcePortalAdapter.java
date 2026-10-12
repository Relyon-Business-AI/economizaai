package com.relyon.economizaai.service.sefaz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ceará NFC-e portal (nfce.sefaz.ce.gov.br). The consult page is an AngularJS
 * shell — the data actually comes from a JSON API its own {@code app.js} calls
 * (reverse-engineered 2026-10-12, validated live with a real Centerbox nota of
 * 68 items): {@code POST /nfce/api/notasFiscal/qrcodev3/} with
 * {@code {chave_acesso, versao_qrcode: 3, tipo_ambiente: 1}} answers
 * {@code {xml: <rendered DANFE html>, erro}}. No captcha, no session, and the
 * v3 consult takes a BARE chave — no QR signature needed, which also makes
 * manual chave entry and mass import work for CE. This retired the
 * "CE is Infosimples-only" assumption (~R$0.24/nota) — the paid fallback now
 * only rescues genuine fetch failures, like everywhere else.
 *
 * <p>The returned markup is the standard responsive-DANFE layout ({@code #u20.txtTopo}
 * header + {@code tabResult} items — same as SVRS), so parsing is delegated to
 * {@link ResponsiveDanfeParser}.
 *
 * <p>Retry classification per CLAUDE.md: transient (HTTP failure/5xx, empty
 * response) retries up to {@code max-attempts}; deterministic (portal answered
 * with an {@code erro} — unknown chave, invalid QR) propagates immediately
 * WITHOUT burning a paid fallback query on a nota the portal itself rejected.
 */
@Slf4j
@Component
public class CearaNfcePortalAdapter implements SefazAdapter {

    private static final String API_URL = "http://nfce.sefaz.ce.gov.br/nfce/api/notasFiscal/qrcodev3/";
    private static final String PRODUCTION_AMBIENTE = "1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxAttempts;
    private final long retryDelayMs;

    public CearaNfcePortalAdapter(RestClient.Builder builder,
                                  @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizaai.ingestion.sefaz.retry.max-attempts:5}") int maxAttempts,
                                  @Value("${economizaai.ingestion.sefaz.retry.delay-ms:5000}") long retryDelayMs,
                                  @Value("${economizaai.ingestion.sefaz.ce-user-agent:Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36}") String userAgent) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Referer", "http://nfce.sefaz.ce.gov.br/pages/ShowNFCe.html")
                .requestFactory(requestFactory)
                .build();
        log.info("CearaNfcePortalAdapter active (qrcodev3 JSON api) retry maxAttempts={}", this.maxAttempts);
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return EnumSet.of(UnidadeFederativa.CE);
    }

    @Override
    public String fetchHtml(String qrPayload) {
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        RuntimeException lastTransient = null;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchOnce(chave);
            } catch (SefazDeterministicFetchException portalRejection) {
                throw portalRejection;
            } catch (HttpClientErrorException clientError) {
                // 4xx — deterministic (bad request/unknown route): retrying won't help.
                log.warn("ce.fetch.client_error status={} chave={}", clientError.getStatusCode(), LogMasker.chave(chave));
                throw new SefazDeterministicFetchException(UnidadeFederativa.CE.name());
            } catch (SefazFetchException | RestClientException transientEx) {
                lastTransient = transientEx;
                log.warn("ce.fetch transient attempt={}/{} chave={} reason={}: {}",
                        attempt, maxAttempts, LogMasker.chave(chave),
                        transientEx.getClass().getSimpleName(), abbreviate(transientEx.getMessage()));
                sleepBetweenAttempts(attempt);
            }
        }
        throw lastTransient instanceof SefazFetchException fetchFailure
                ? fetchFailure
                : new SefazFetchException(UnidadeFederativa.CE.name());
    }

    /** One API consult. Protected: the retry-classification test overrides the HTTP seam below. */
    private String fetchOnce(String chave) {
        var responseBody = postConsult(consultBody(chave));
        if (responseBody == null || responseBody.isBlank()) {
            throw new SefazFetchException(UnidadeFederativa.CE.name());
        }
        try {
            var response = objectMapper.readTree(responseBody);
            var erro = response.path("erro").asText("");
            if (!erro.isBlank()) {
                // The portal answered and rejected the consult (unknown chave, invalid
                // QR) — deterministic; don't retry and don't spend the paid fallback.
                log.warn("ce.fetch.portal_error chave={} erro={}", LogMasker.chave(chave), abbreviate(erro));
                throw new SefazDeterministicFetchException(UnidadeFederativa.CE.name());
            }
            var danfeHtml = response.path("xml").asText("");
            if (danfeHtml.isBlank()) {
                throw new SefazFetchException(UnidadeFederativa.CE.name());
            }
            log.info("ce.fetch ok chave={} danfeChars={}", LogMasker.chave(chave), danfeHtml.length());
            return danfeHtml;
        } catch (SefazFetchException classified) { // includes the deterministic subclass
            throw classified;
        } catch (Exception malformedJson) {
            // Not the API's JSON shape (bot wall / maintenance page) — transient.
            log.warn("ce.fetch.unparseable_response chave={} bytes={}", LogMasker.chave(chave), responseBody.length());
            throw new SefazFetchException(UnidadeFederativa.CE.name());
        }
    }

    private String consultBody(String chave) {
        return objectMapper.createObjectNode()
                .put("chave_acesso", chave)
                .put("versao_qrcode", "3")
                .put("tipo_ambiente", PRODUCTION_AMBIENTE)
                .toString();
    }

    /** Raw HTTP seam — isolated so the retry-classification test can stub the portal. */
    protected String postConsult(String jsonBody) {
        return restClient.post()
                .uri(API_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
    }

    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
    }

    private static String abbreviate(String message) {
        if (message == null) return "(no message)";
        return message.length() <= 90 ? message : message.substring(0, 90);
    }

    private void sleepBetweenAttempts(int attempt) {
        if (attempt >= maxAttempts || retryDelayMs == 0) return;
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SefazFetchException(UnidadeFederativa.CE.name());
        }
    }
}
