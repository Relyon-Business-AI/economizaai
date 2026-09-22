package com.relyon.economizaai.service.analytics.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaConversionsClientTest {

    private static final String PIXEL_ID = "1088687550381259";
    private static final String TOKEN = "capi-token";
    private static final String BASE_URL = "https://graph.test";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer server;
    private MetaConversionsClient client;
    private MetaConversionsProperties properties;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new MetaConversionsProperties();
        properties.setEnabled(true);
        properties.setPixelId(PIXEL_ID);
        properties.setToken(TOKEN);
        properties.setApiVersion("v21.0");
        properties.setGraphBaseUrl(BASE_URL);
        client = new MetaConversionsClient(builder.build(), properties);
    }

    @Test
    void sendsHashedEmailFbcAndDedupeId() throws Exception {
        var capturedBody = new AtomicReference<String>();
        server.expect(requestTo(BASE_URL + "/v21.0/" + PIXEL_ID + "/events?access_token=" + TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> capturedBody.set(request.getBody().toString()))
                .andRespond(withSuccess("{\"events_received\":1}", MediaType.APPLICATION_JSON));

        client.sendCompleteRegistration("42-completeregistration", "User@Example.com ", "abc123",
                "203.0.113.7", "Mozilla/5.0", "email", true, 1_700_000_000L);

        server.verify();
        JsonNode event = objectMapper.readTree(capturedBody.get()).path("data").path(0);
        assertThat(event.path("event_name").asText()).isEqualTo("CompleteRegistration");
        assertThat(event.path("action_source").asText()).isEqualTo("website");
        assertThat(event.path("event_id").asText()).isEqualTo("42-completeregistration");
        assertThat(event.path("event_time").asLong()).isEqualTo(1_700_000_000L);

        JsonNode userData = event.path("user_data");
        assertThat(userData.path("em").path(0).asText()).isEqualTo(sha256("user@example.com"));
        assertThat(userData.path("client_ip_address").asText()).isEqualTo("203.0.113.7");
        assertThat(userData.path("client_user_agent").asText()).isEqualTo("Mozilla/5.0");
        assertThat(userData.path("fbc").asText()).isEqualTo("fb.1.1700000000000.abc123");
        assertThat(event.path("custom_data").path("registration_method").asText()).isEqualTo("email");
    }

    @Test
    void omitsFbcWhenNoClickId() throws Exception {
        var capturedBody = new AtomicReference<String>();
        server.expect(requestTo(BASE_URL + "/v21.0/" + PIXEL_ID + "/events?access_token=" + TOKEN))
                .andExpect(request -> capturedBody.set(request.getBody().toString()))
                .andRespond(withSuccess("{\"events_received\":1}", MediaType.APPLICATION_JSON));

        client.sendCompleteRegistration("7-completeregistration", "a@b.com", null,
                null, null, "google", false, 1_700_000_000L);

        server.verify();
        JsonNode event = objectMapper.readTree(capturedBody.get()).path("data").path(0);
        assertThat(event.path("action_source").asText()).isEqualTo("app");
        assertThat(event.path("user_data").has("fbc")).isFalse();
        assertThat(event.path("user_data").has("client_ip_address")).isFalse();
    }

    @Test
    void swallowsServerErrorWithoutThrowing() {
        server.expect(requestTo(BASE_URL + "/v21.0/" + PIXEL_ID + "/events?access_token=" + TOKEN))
                .andRespond(withServerError());

        client.sendCompleteRegistration("1-completeregistration", "a@b.com", null,
                null, null, "email", true, 1_700_000_000L);

        server.verify();
    }

    private String sha256(String value) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        var hex = new StringBuilder(bytes.length * 2);
        for (var singleByte : bytes) {
            hex.append(Character.forDigit((singleByte >> 4) & 0xF, 16));
            hex.append(Character.forDigit(singleByte & 0xF, 16));
        }
        return hex.toString();
    }
}
