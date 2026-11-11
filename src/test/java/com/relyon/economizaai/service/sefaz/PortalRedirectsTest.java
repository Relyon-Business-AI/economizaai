package com.relyon.economizaai.service.sefaz;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PortalRedirectsTest {

    private static final String CURRENT = "http://nfce.sefaz.pe.gov.br/nfce/consulta?p=123|3|1";

    @Test
    void resolveLocation_keepsAnAbsoluteTarget() {
        assertThat(PortalRedirects.resolveLocation(CURRENT, "https://nfce.sefaz.pe.gov.br:444/nfce/consulta?p=123|3|1"))
                .isEqualTo("https://nfce.sefaz.pe.gov.br:444/nfce/consulta?p=123|3|1");
    }

    @Test
    void resolveLocation_resolvesRootRelativeAgainstTheOrigin() {
        assertThat(PortalRedirects.resolveLocation(CURRENT, "/outra/pagina?x=1"))
                .isEqualTo("http://nfce.sefaz.pe.gov.br/outra/pagina?x=1");
    }

    @Test
    void resolveLocation_resolvesPathRelativeAgainstTheCurrentDirectory() {
        assertThat(PortalRedirects.resolveLocation(CURRENT, "danfe.aspx?p=123"))
                .isEqualTo("http://nfce.sefaz.pe.gov.br/nfce/danfe.aspx?p=123");
        assertThat(PortalRedirects.resolveLocation("https://www.sefaz.rs.gov.br", "danfe"))
                .isEqualTo("https://www.sefaz.rs.gov.br/danfe");
    }

    @Test
    void resolveLocation_resolvesSchemeRelativeWithTheCurrentScheme() {
        assertThat(PortalRedirects.resolveLocation(CURRENT, "//outro.host.example/x"))
                .isEqualTo("http://outro.host.example/x");
    }

    @Test
    void isHttps_matchesSchemeCaseInsensitively() {
        assertThat(PortalRedirects.isHttps("HTTPS://www.sefaz.rs.gov.br/x")).isTrue();
        assertThat(PortalRedirects.isHttps("http://www.sefaz.rs.gov.br/x")).isFalse();
    }

    /** Against a local in-process server: the client must hand the 3xx back instead of following it. */
    @Test
    void requestFactory_doesNotFollowASameSchemeRedirectOnItsOwn() throws IOException {
        var targetHits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            var body = "followed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var restClient = RestClient.builder()
                    .requestFactory(PortalRedirects.requestFactoryWithoutAutoRedirects(2000, 2000))
                    .build();

            var response = restClient.get()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/start")
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/target");
            assertThat(targetHits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }
}
