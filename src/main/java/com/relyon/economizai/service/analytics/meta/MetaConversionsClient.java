package com.relyon.economizai.service.analytics.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Sends server-side events to the Meta Conversions API (the Pixel's {@code /events}
 * edge). PII is hashed (SHA-256) per Meta's requirements; the ad-click is passed as
 * {@code fbc} so Meta can attribute the event to the campaign. Never throws to the
 * caller — a failed send just logs and is dropped (advisory tracking).
 */
@Slf4j
@Service
public class MetaConversionsClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetaConversionsProperties properties;

    @Autowired
    public MetaConversionsClient(RestClient.Builder builder, MetaConversionsProperties properties) {
        this(builder.requestFactory(timeoutFactory()).build(), properties);
    }

    /** Test seam: inject a pre-built (e.g. mock-bound) RestClient. */
    MetaConversionsClient(RestClient restClient, MetaConversionsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15000);
        return requestFactory;
    }

    /**
     * Reports a "CompleteRegistration" event. {@code eventId} must match the one the
     * browser Pixel would send so Meta deduplicates. {@code fbclid} may be null
     * (then no {@code fbc} is sent — email/IP still help matching).
     */
    public void sendCompleteRegistration(String eventId, String email, String fbclid,
                                         String clientIp, String userAgent, String method,
                                         boolean webActionSource, long eventTimeSeconds) {
        var payload = buildPayload(eventId, email, fbclid, clientIp, userAgent, method,
                webActionSource, eventTimeSeconds);
        var url = properties.getGraphBaseUrl()
                + "/" + properties.getApiVersion()
                + "/" + properties.getPixelId() + "/events"
                + "?access_token=" + encode(properties.getToken());
        try {
            var response = restClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload.toString())
                    .retrieve()
                    .body(String.class);
            var events = parseEventsReceived(response);
            log.info("meta.capi.sent event=CompleteRegistration method={} received={}", method, events);
        } catch (RestClientException ex) {
            log.warn("meta.capi.failed event=CompleteRegistration method={} reason={}", method, ex.getMessage());
        }
    }

    private ObjectNode buildPayload(String eventId, String email, String fbclid, String clientIp,
                                    String userAgent, String method, boolean webActionSource,
                                    long eventTimeSeconds) {
        var userData = objectMapper.createObjectNode();
        var hashedEmail = sha256(email);
        if (hashedEmail != null) {
            ArrayNode emails = userData.putArray("em");
            emails.add(hashedEmail);
        }
        if (clientIp != null && !clientIp.isBlank()) userData.put("client_ip_address", clientIp);
        if (userAgent != null && !userAgent.isBlank()) userData.put("client_user_agent", userAgent);
        if (fbclid != null && !fbclid.isBlank()) {
            userData.put("fbc", "fb.1." + (eventTimeSeconds * 1000L) + "." + fbclid);
        }

        var event = objectMapper.createObjectNode();
        event.put("event_name", "CompleteRegistration");
        event.put("event_time", eventTimeSeconds);
        event.put("action_source", webActionSource ? "website" : "app");
        event.put("event_id", eventId);
        event.set("user_data", userData);
        var customData = event.putObject("custom_data");
        customData.put("registration_method", method);

        var payload = objectMapper.createObjectNode();
        ArrayNode data = payload.putArray("data");
        data.add(event);
        if (properties.getTestEventCode() != null && !properties.getTestEventCode().isBlank()) {
            payload.put("test_event_code", properties.getTestEventCode());
        }
        return payload;
    }

    /** SHA-256 hex of the normalized (trim + lowercase) value, per Meta's advanced matching. */
    private String sha256(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private int parseEventsReceived(String response) {
        if (response == null || response.isBlank()) return 0;
        try {
            return objectMapper.readTree(response).path("events_received").asInt(0);
        } catch (Exception ex) {
            return 0;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
