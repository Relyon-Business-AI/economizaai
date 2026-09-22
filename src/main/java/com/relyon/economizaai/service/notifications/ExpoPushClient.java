package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper over the Expo Push API. Submits a single message to
 * {@code https://exp.host/--/api/v2/push/send} and parses the per-message
 * ticket — Expo always returns 200 OK with a status of {@code ok} or
 * {@code error} per message.
 *
 * <p>Auth is optional. Setting {@code EXPO_ACCESS_TOKEN} (env) attaches a
 * bearer token, which raises rate limits and lets Expo correlate sends
 * with the project's analytics dashboard. Unauthenticated sends still work
 * for moderate volumes.
 *
 * <p>Receipt verification (final delivery confirmation via
 * {@code /push/getReceipts}) is intentionally skipped — adds latency and
 * isn't needed at our scale. A "ticket OK" means Expo accepted the
 * message and will route it to FCM/APNs.
 */
@Slf4j
@Component
public class ExpoPushClient {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExpoPushClient(RestClient.Builder builder,
                          @Value("${economizaai.notifications.push.expo.access-token:}") String accessToken) {
        // Explicit timeouts — without them the request thread can hang indefinitely
        // waiting on a slow/dead Expo endpoint, blocking the calling controller.
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        var configured = builder
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Accept-Encoding", "gzip, deflate");
        if (accessToken != null && !accessToken.isBlank()) {
            configured = configured.defaultHeader("Authorization", "Bearer " + accessToken);
        }
        this.restClient = configured.build();
    }

    public Result send(String token, String title, String body, Map<String, String> data) {
        var payload = new HashMap<String, Object>();
        payload.put("to", token);
        payload.put("title", title);
        payload.put("body", body);
        if (data != null && !data.isEmpty()) payload.put("data", data);
        try {
            return parseTicket(postToExpo(payload));
        } catch (Exception ex) {
            return Result.error(ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /** Raw POST — isolated as a seam so response parsing is unit-testable without HTTP. */
    protected String postToExpo(Map<String, Object> payload) {
        return restClient.post().uri(EXPO_PUSH_URL).body(payload).retrieve().body(String.class);
    }

    private Result parseTicket(String response) throws Exception {
        if (response == null || response.isBlank()) {
            return Result.error("EmptyResponse", "Expo returned empty body");
        }
        var root = objectMapper.readTree(response);
        var ticket = root.get("data");
        if (ticket == null) {
            return Result.error("MalformedResponse", response.length() > 200 ? response.substring(0, 200) : response);
        }
        // Single-send returns object; batch returns array. We always send single.
        if (ticket.isArray()) ticket = ticket.get(0);
        if (ticket == null) {
            return Result.error("MalformedResponse", "missing ticket entry");
        }
        var status = textOrNull(ticket, "status");
        if ("ok".equalsIgnoreCase(status)) {
            return Result.ok(textOrNull(ticket, "id"));
        }
        var details = ticket.get("details");
        var detailsError = details != null ? textOrNull(details, "error") : null;
        return Result.error(detailsError != null ? detailsError : "Unknown",
                textOrNull(ticket, "message"));
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    public record Result(boolean ok, String ticketId, String errorCode, String errorMessage) {
        public static Result ok(String ticketId) {
            return new Result(true, ticketId, null, null);
        }
        public static Result error(String code, String message) {
            return new Result(false, null, code, message);
        }
    }
}
