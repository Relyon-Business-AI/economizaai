package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;

/**
 * Consulta pública por CHAVE PURA do Amazonas — GET simples, SEM captcha e SEM assinatura de QR
 * (validado 2026-11: nota de 2023 responde). O portal serve o DANFE responsivo padrão nacional
 * (mesmo layout de BA/SVRS), então o {@link ResponsiveDanfeParser} genérico lê os itens direto —
 * é o "novo RS". A página declara {@code charset=ISO-8859-1}, então decodificamos nesse encoding
 * (UTF-8 corromperia os acentos das descrições).
 */
@Slf4j
@Component
public class AmChaveReconsultClient {

    private static final String CONSULT_URL =
            "https://sistemas.sefaz.am.gov.br/nfceweb/consultarNFCe.jsp?p=";

    private final RestClient restClient;

    public AmChaveReconsultClient(RestClient.Builder builder,
                                  @Value("${economizaai.ingestion.sefaz.timeout-ms:30000}") int timeoutMs,
                                  @Value("${economizaai.ingestion.sefaz.user-agent:economizai}") String userAgent) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .requestFactory(requestFactory)
                .build();
    }

    public String fetchByChave(String chave) {
        try {
            var response = restClient.get().uri(CONSULT_URL + chave).retrieve().toEntity(byte[].class);
            var bytes = response.getBody() == null ? new byte[0] : response.getBody();
            var html = new String(bytes, StandardCharsets.ISO_8859_1);
            log.info("am_reconsult.fetched chave={} bytes={}", abbrev(chave), html.length());
            return html;
        } catch (RestClientException ex) {
            log.warn("am_reconsult.http_failed chave={} reason={}", abbrev(chave), ex.getClass().getSimpleName());
            throw new SefazFetchException(UnidadeFederativa.AM.name());
        }
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? "????" : chave.substring(0, 8);
    }
}
