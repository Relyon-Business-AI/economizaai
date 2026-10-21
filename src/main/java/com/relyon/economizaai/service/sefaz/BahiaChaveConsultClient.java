package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Fetches a BA nota's DANFE from a <b>bare 44-digit chave</b> (no QR signature)
 * for import-by-chave. The public consult at
 * {@code nfe.sefaz.ba.gov.br/.../NFCEC_consulta_chave_acesso.aspx} is gated by a
 * home-grown image captcha ("anti-robô"): GET the form (seeds the ASP.NET session
 * + VIEWSTATE), GET the captcha image on the same session, solve it via
 * {@link CaptchaSolver#solveImageToText(byte[])}, then POST chave + código.
 * The response is the shared responsive DANFE layout ({@link ResponsiveDanfeParser}).
 *
 * <p>Validated live with a real prod nota on 2026-10-21 (fixture under
 * {@code fixtures/sefaz/ba/}). Per the captcha-portal rule, the whole flow is
 * wrapped in a bounded retry that RE-SOLVES a fresh image when the portal
 * rejects the code (each attempt costs a paid solve, ~R$0,003).
 */
@Slf4j
@Component
public class BahiaChaveConsultClient {

    private static final String ENTRY_URL =
            "http://nfe.sefaz.ba.gov.br/servicos/nfce/modulos/geral/NFCEC_consulta_chave_acesso.aspx";
    private static final String CAPTCHA_IMAGE_URL =
            "http://nfe.sefaz.ba.gov.br/servicos/nfce/modulos/AntiRobo/NFCEC_anti_robo.aspx";

    private static final Pattern VIEWSTATE = hiddenField("__VIEWSTATE");
    private static final Pattern VIEWSTATE_GENERATOR = hiddenField("__VIEWSTATEGENERATOR");
    private static final Pattern EVENT_VALIDATION = hiddenField("__EVENTVALIDATION");

    private final RestClient restClient;
    private final CaptchaSolver captchaSolver;
    private final int maxAttempts;

    public BahiaChaveConsultClient(RestClient.Builder builder,
                                   CaptchaSolver captchaSolver,
                                   @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                   @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent,
                                   @Value("${economizaai.ingestion.sefaz.ba-reconsult-max-attempts:3}") int maxAttempts) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .requestFactory(requestFactory)
                .build();
        this.captchaSolver = captchaSolver;
        this.maxAttempts = maxAttempts;
    }

    public boolean isAvailable() {
        return captchaSolver.isConfigured();
    }

    /** Full session flow; retries with a FRESH session + solve when the portal rejects the code. */
    public String fetchByChave(String chave) {
        CaptchaSolveFailedException lastSolveFailure = null;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                var html = attemptFetch(chave);
                if (html != null) {
                    log.info("ba_reconsult.fetched chave={} attempt={}/{} bytes={}",
                            abbrev(chave), attempt, maxAttempts, html.length());
                    return html;
                }
                log.warn("ba_reconsult.captcha_rejected chave={} attempt={}/{} — re-solving",
                        abbrev(chave), attempt, maxAttempts);
            } catch (CaptchaSolveFailedException ex) {
                lastSolveFailure = ex;
                log.warn("ba_reconsult.solve_failed chave={} attempt={}/{} reason={}",
                        abbrev(chave), attempt, maxAttempts, ex.getMessage());
            } catch (RestClientException ex) {
                log.warn("ba_reconsult.http_failed chave={} reason={}", abbrev(chave), ex.getClass().getSimpleName());
                throw new SefazFetchException(UnidadeFederativa.BA.name());
            }
        }
        if (lastSolveFailure != null) throw lastSolveFailure;
        throw new SefazFetchException(UnidadeFederativa.BA.name());
    }

    /** One session round-trip. Returns the DANFE html, or null when the captcha was rejected. */
    private String attemptFetch(String chave) {
        var entry = restClient.get().uri(ENTRY_URL).retrieve().toEntity(byte[].class);
        var cookie = sessionCookie(entry.getHeaders());
        var entryHtml = new String(entry.getBody() == null ? new byte[0] : entry.getBody(), StandardCharsets.UTF_8);

        var viewState = extract(VIEWSTATE, entryHtml);
        var viewStateGenerator = extract(VIEWSTATE_GENERATOR, entryHtml);
        var eventValidation = extract(EVENT_VALIDATION, entryHtml);
        if (viewState == null || eventValidation == null) {
            // página mudou de formato — determinístico, não gasta solve
            log.warn("ba_reconsult.entry_markup_changed chave={}", abbrev(chave));
            throw new SefazFetchException(UnidadeFederativa.BA.name());
        }

        var imageRequest = restClient.get().uri(CAPTCHA_IMAGE_URL);
        if (cookie != null) imageRequest = imageRequest.header(HttpHeaders.COOKIE, cookie);
        var image = imageRequest.retrieve().body(byte[].class);
        if (image == null || image.length == 0) {
            throw new SefazFetchException(UnidadeFederativa.BA.name());
        }

        var code = captchaSolver.solveImageToText(image);

        var form = "__LASTFOCUS=&__EVENTTARGET=&__EVENTARGUMENT="
                + "&__VIEWSTATE=" + encode(viewState)
                + "&__VIEWSTATEGENERATOR=" + encode(viewStateGenerator == null ? "" : viewStateGenerator)
                + "&__EVENTVALIDATION=" + encode(eventValidation)
                + "&txt_chave_acesso=" + chave
                + "&txt_cod_antirobo=" + encode(code)
                + "&btn_consulta_completa=" + encode("Consulta completa");
        var postRequest = restClient.post()
                .uri(ENTRY_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.REFERER, ENTRY_URL);
        if (cookie != null) postRequest = postRequest.header(HttpHeaders.COOKIE, cookie);
        var response = postRequest.body(form).retrieve().body(byte[].class);
        var html = new String(response == null ? new byte[0] : response, StandardCharsets.UTF_8);

        if (looksLikeDanfe(html)) return html;
        if (looksLikeEntryForm(html)) return null; // captcha errado → retry re-solve
        // nem DANFE nem o form de novo: chave inexistente/erro do portal — determinístico
        log.warn("ba_reconsult.unexpected_response chave={} bytes={}", abbrev(chave), html.length());
        throw new SefazFetchException(UnidadeFederativa.BA.name());
    }

    static boolean looksLikeDanfe(String html) {
        return html.contains("DOCUMENTO AUXILIAR") || html.contains("txtTit");
    }

    static boolean looksLikeEntryForm(String html) {
        return html.contains("txt_cod_antirobo");
    }

    static String extract(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Pattern hiddenField(String name) {
        return Pattern.compile("name=\"" + name + "\"[^>]*value=\"([^\"]*)\"");
    }

    private static String sessionCookie(HttpHeaders headers) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null || setCookies.isEmpty()) return null;
        return setCookies.stream()
                .map(cookieHeader -> cookieHeader.split(";", 2)[0])
                .reduce((first, second) -> first + "; " + second)
                .orElse(null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }
}
