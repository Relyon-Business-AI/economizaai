package com.relyon.economizaai.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingCorsProcessorTest {

    private final LoggingCorsProcessor processor = new LoggingCorsProcessor();

    private CorsConfiguration configurationAllowing(String origin) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origin));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        return configuration;
    }

    private MockHttpServletRequest corsRequestFrom(String origin) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(HttpHeaders.ORIGIN, origin);
        return request;
    }

    @Test
    void rejectsRequestFromDisallowedOrigin() throws IOException {
        var response = new MockHttpServletResponse();

        var accepted = processor.processRequest(configurationAllowing("https://dashboard.economizaai.app"),
                corsRequestFrom("https://evil.example.com"), response);

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsRequestFromAllowedOrigin() throws IOException {
        var response = new MockHttpServletResponse();

        var accepted = processor.processRequest(configurationAllowing("https://dashboard.economizaai.app"),
                corsRequestFrom("https://dashboard.economizaai.app"), response);

        assertThat(accepted).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("https://dashboard.economizaai.app");
    }

    @Test
    void acceptsNonCorsRequestWithoutOriginHeader() throws IOException {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        var accepted = processor.processRequest(configurationAllowing("https://dashboard.economizaai.app"),
                request, response);

        assertThat(accepted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
