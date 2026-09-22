package com.relyon.economizaai.service.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ExpoPushClient} through the {@code postToExpo} seam so the
 * response-parsing logic is exercised without real HTTP.
 */
class ExpoPushClientTest {

    private final AtomicReference<Map<String, Object>> captured = new AtomicReference<>();

    private ExpoPushClient clientReturning(String response) {
        return new ExpoPushClient(RestClient.builder(), "") {
            @Override
            protected String postToExpo(Map<String, Object> payload) {
                captured.set(payload);
                return response;
            }
        };
    }

    private ExpoPushClient clientThrowing() {
        return new ExpoPushClient(RestClient.builder(), "") {
            @Override
            protected String postToExpo(Map<String, Object> payload) {
                throw new RuntimeException("connection refused");
            }
        };
    }

    @Test
    void okTicketFromSingleObjectResponse() {
        var result = clientReturning("{\"data\":{\"status\":\"ok\",\"id\":\"ticket-1\"}}")
                .send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertTrue(result.ok());
        assertEquals("ticket-1", result.ticketId());
    }

    @Test
    void okTicketWhenResponseIsArray() {
        var result = clientReturning("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-2\"}]}")
                .send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertTrue(result.ok());
        assertEquals("ticket-2", result.ticketId());
    }

    @Test
    void errorWithDetailsErrorCode() {
        var result = clientReturning(
                "{\"data\":{\"status\":\"error\",\"message\":\"token expired\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}")
                .send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("DeviceNotRegistered", result.errorCode());
    }

    @Test
    void errorFallsBackToUnknownWhenDetailsMissing() {
        var result = clientReturning("{\"data\":{\"status\":\"error\",\"message\":\"oops\"}}")
                .send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("Unknown", result.errorCode());
    }

    @Test
    void emptyBodyIsError() {
        var result = clientReturning("").send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("EmptyResponse", result.errorCode());
    }

    @Test
    void malformedWhenDataNodeAbsent() {
        var result = clientReturning("{\"errors\":[]}").send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("MalformedResponse", result.errorCode());
    }

    @Test
    void malformedWhenArrayEmpty() {
        var result = clientReturning("{\"data\":[]}").send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("MalformedResponse", result.errorCode());
    }

    @Test
    void transportErrorIsCaught() {
        var result = clientThrowing().send("ExponentPushToken[abc]", "Hi", "Body", null);
        assertFalse(result.ok());
        assertEquals("RuntimeException", result.errorCode());
    }

    @Test
    void payloadIncludesDataKeyWhenExtrasPresent() {
        clientReturning("{\"data\":{\"status\":\"ok\",\"id\":\"x\"}}")
                .send("ExponentPushToken[abc]", "Hi", "Body", Map.of("receiptId", "r1"));
        assertTrue(captured.get().containsKey("data"));
        assertEquals("Hi", captured.get().get("title"));
    }

    @Test
    void payloadOmitsDataKeyWhenExtrasAbsent() {
        clientReturning("{\"data\":{\"status\":\"ok\",\"id\":\"x\"}}")
                .send("ExponentPushToken[abc]", "Hi", "Body", Map.of());
        assertFalse(captured.get().containsKey("data"));
    }
}
