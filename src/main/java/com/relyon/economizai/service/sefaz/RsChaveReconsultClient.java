package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fetches an RS nota's DANFE from a <b>bare 44-digit chave</b> (no QR signature)
 * for the reconsult bulk import. Two public RS paths, one per document model:
 *
 * <ul>
 *   <li><b>NFC-e (modelo 65)</b> — the legacy ASP portal
 *   {@code sefaz.rs.gov.br/ASP/AAE_ROOT/NFE/SAT-WEB-NFE-NFC_*.asp}: GET the form
 *   page (sets an ASP session cookie), then POST the chave to render the DANFE.
 *   Serves windows-1252/latin-1.</li>
 *   <li><b>NF-e (modelo 55)</b> — the SVRS public consult
 *   {@code dfe-portal.svrs.rs.gov.br/NFe/ConsultaPublicaDfe}: a single POST
 *   renders the "Produtos e Serviços" view. Serves UTF-8. (reCAPTCHA is present
 *   on the page but not enforced on this POST — may harden under volume.)</li>
 * </ul>
 *
 * Both were validated 2026-09-22 (see {@code docs/ONBOARDING_IMPORT.md}). The URLs
 * are fixed constants (no user-controlled host), so there is no SSRF surface.
 */
@Slf4j
@Component
public class RsChaveReconsultClient {

    private static final String SATWEB_FORM_URL =
            "https://www.sefaz.rs.gov.br/ASP/AAE_ROOT/NFE/SAT-WEB-NFE-NFC_1.asp?chaveNfe=";
    private static final String SATWEB_RESULT_URL =
            "https://www.sefaz.rs.gov.br/ASP/AAE_ROOT/NFE/SAT-WEB-NFE-NFC_2.asp";
    private static final String SVRS_NFE_CONSULT_URL =
            "https://dfe-portal.svrs.rs.gov.br/NFe/ConsultaPublicaDfe";
    // windows-1252 covers the latin-1 range plus the smart-quotes the ASP portal emits.
    private static final Charset SATWEB_CHARSET = Charset.forName("windows-1252");

    private final RestClient restClient;

    public RsChaveReconsultClient(RestClient.Builder builder,
                                  @Value("${economizai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .requestFactory(requestFactory)
                .build();
    }

    /** SAT-WEB NFC-e (modelo 65): GET the form to seed the session cookie, then POST the chave. */
    public String fetchNfceSatWeb(String chave) {
        try {
            var formResponse = restClient.get()
                    .uri(SATWEB_FORM_URL + chave)
                    .retrieve()
                    .toEntity(byte[].class);
            var cookie = sessionCookie(formResponse.getHeaders());
            var body = "HML=false&chaveNFe=" + chave + "&Action=Avan%E7ar";
            var request = restClient.post()
                    .uri(SATWEB_RESULT_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (cookie != null) {
                request = request.header(HttpHeaders.COOKIE, cookie);
            }
            var html = request.body(body).retrieve().body(byte[].class);
            return decode(html, SATWEB_CHARSET);
        } catch (HttpClientErrorException ex) {
            log.warn("reconsult.satweb.client_error status={} chave={}", ex.getStatusCode(), abbrev(chave));
            throw new SefazFetchException(UnidadeFederativa.RS.name());
        } catch (RestClientException ex) {
            log.warn("reconsult.satweb.failed chave={} reason={}", abbrev(chave), ex.getClass().getSimpleName());
            throw new SefazFetchException(UnidadeFederativa.RS.name());
        }
    }

    /** SVRS NF-e (modelo 55): a single POST renders the Produtos e Serviços view. */
    public String fetchNfe55(String chave) {
        try {
            var body = "sistema=Dfe&EhConsultaPublicaSiteSefaz=True&Ambiente=1&ChaveAcessoDfe=" + chave;
            var html = restClient.post()
                    .uri(SVRS_NFE_CONSULT_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            return decode(html, StandardCharsets.UTF_8);
        } catch (HttpClientErrorException ex) {
            log.warn("reconsult.nfe55.client_error status={} chave={}", ex.getStatusCode(), abbrev(chave));
            throw new SefazFetchException(UnidadeFederativa.RS.name());
        } catch (RestClientException ex) {
            log.warn("reconsult.nfe55.failed chave={} reason={}", abbrev(chave), ex.getClass().getSimpleName());
            throw new SefazFetchException(UnidadeFederativa.RS.name());
        }
    }

    private static String sessionCookie(HttpHeaders headers) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null || setCookies.isEmpty()) {
            return null;
        }
        return setCookies.stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .reduce((first, second) -> first + "; " + second)
                .orElse(null);
    }

    private static String decode(byte[] body, Charset charset) {
        return body == null ? "" : new String(body, charset);
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }
}
