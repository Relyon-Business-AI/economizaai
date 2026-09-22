package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces a production error seen against the live categorizer:
 *
 * <pre>MissingServletRequestParameterException: Required request parameter
 * 'description' for method parameter type List is not present</pre>
 *
 * <p>The {@code @WebMvcTest} slice does NOT surface this — MockMvc's argument
 * resolution honors {@code defaultValue} for a missing multi-value param. Only
 * the real servlet container (Tomcat) reproduces it, so this test boots a real
 * port and calls {@code GET /api/v1/categorizer/classify} with no
 * {@code description} param, exactly as the failing production request did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CategorizerClassifyMissingParamTest {

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String registerAndGetToken() throws Exception {
        var body = "{\"name\":\"Param Tester\",\"email\":\"param@test.com\",\"password\":\"password123\","
                + "\"acceptedTermsVersion\":\"1.0\",\"acceptedPrivacyVersion\":\"1.0\"}";
        var req = HttpRequest.newBuilder(URI.create(url("/api/v1/auth/register")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(resp.body());
        return json.get("token").asText();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int statusFor(String token, String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void classify_variantsThatCouldTriggerMissingParam_allReturnOk() throws Exception {
        var token = registerAndGetToken();

        // The live bug surfaced as a 400 MissingServletRequestParameterException.
        // Probe every shape the production request could have taken.
        assertThat(statusFor(token, "/api/v1/categorizer/classify")).isEqualTo(200);          // no param at all
        assertThat(statusFor(token, "/api/v1/categorizer/classify?description")).isEqualTo(200);   // key, no '='
        assertThat(statusFor(token, "/api/v1/categorizer/classify?description=")).isEqualTo(200);  // key, empty value
        assertThat(statusFor(token, "/api/v1/categorizer/classify?other=x")).isEqualTo(200);   // unrelated param only
        assertThat(statusFor(token, "/api/v1/categorizer/ml/predict")).isEqualTo(200);         // sibling endpoint, no param
    }
}
