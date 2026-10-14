package com.relyon.economizaai.service.notifications;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportChartTokenServiceTest {

    private final ReportChartTokenService service = new ReportChartTokenService("a-very-long-dev-secret-key-for-hs256-at-least-256-bits");

    @Test
    void mintThenVerify_roundTripsTheUserId() {
        var userId = UUID.randomUUID();
        var token = service.mint(userId);
        assertEquals(userId, service.verify(token).orElseThrow());
    }

    @Test
    void verify_rejectsTamperedSignature() {
        var token = service.mint(UUID.randomUUID());
        var tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A");
        assertTrue(service.verify(tampered).isEmpty());
    }

    @Test
    void verify_rejectsPayloadTamper() {
        var token = service.mint(UUID.randomUUID());
        // swap the payload for a different user's, keep the old signature → must fail
        var otherPayload = service.mint(UUID.randomUUID()).split("~")[0];
        var forged = otherPayload + "~" + token.split("~")[1];
        assertTrue(service.verify(forged).isEmpty());
    }

    @Test
    void verify_rejectsGarbage() {
        assertTrue(service.verify("not-a-token").isEmpty());
        assertTrue(service.verify("").isEmpty());
        assertTrue(service.verify("a~b~c").isEmpty());
    }

    @Test
    void verify_rejectsTokenSignedWithADifferentSecret() {
        var other = new ReportChartTokenService("a-completely-different-secret-value-for-the-hmac-key");
        var token = other.mint(UUID.randomUUID());
        assertTrue(service.verify(token).isEmpty());
    }
}
