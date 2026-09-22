package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushDispatcherTest {

    private static final String VALID_EXPO_TOKEN = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]";

    @Mock private ExpoPushClient expoPushClient;

    private NotificationPayload payload(String token) {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").pushDeviceToken(token).build();
        return new NotificationPayload(user, NotificationType.SYSTEM, "T", "B", Map.of("k", "v"));
    }

    @Test
    void failsWhenUserHasNoToken() {
        var dispatcher = new PushDispatcher(expoPushClient);
        var result = dispatcher.dispatch(payload(null));
        assertFalse(result.delivered());
        verify(expoPushClient, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsNonExpoTokenWithoutCallingApi() {
        var dispatcher = new PushDispatcher(expoPushClient);
        var result = dispatcher.dispatch(payload("fcm-raw-token-xyz"));
        assertFalse(result.delivered());
        assertTrue(result.failureReason().contains("not an Expo push token"));
        verify(expoPushClient, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sendsViaExpoOnValidToken() {
        when(expoPushClient.send(eq(VALID_EXPO_TOKEN), eq("T"), eq("B"), any()))
                .thenReturn(ExpoPushClient.Result.ok("ticket-123"));
        var dispatcher = new PushDispatcher(expoPushClient);

        var result = dispatcher.dispatch(payload(VALID_EXPO_TOKEN));

        assertTrue(result.delivered());
        @SuppressWarnings("unchecked")
        var captor = (ArgumentCaptor<Map<String, String>>) (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(expoPushClient).send(eq(VALID_EXPO_TOKEN), eq("T"), eq("B"), captor.capture());
        var data = captor.getValue();
        assertEquals("SYSTEM", data.get("type"));
        assertEquals("v", data.get("k"));
    }

    @Test
    void rejectsTokenWithEmptyBracketsOrWhitespace() {
        var dispatcher = new PushDispatcher(expoPushClient);

        var emptyBrackets = dispatcher.dispatch(payload("ExponentPushToken[]"));
        assertFalse(emptyBrackets.delivered());
        assertTrue(emptyBrackets.failureReason().contains("not an Expo push token"));

        var spaceInside = dispatcher.dispatch(payload("ExponentPushToken[abc def]"));
        assertFalse(spaceInside.delivered());

        verify(expoPushClient, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void trimsLeadingTrailingWhitespaceBeforeSending() {
        when(expoPushClient.send(eq(VALID_EXPO_TOKEN), anyString(), anyString(), any()))
                .thenReturn(ExpoPushClient.Result.ok("ticket-abc"));
        var dispatcher = new PushDispatcher(expoPushClient);

        var result = dispatcher.dispatch(payload("  " + VALID_EXPO_TOKEN + "\n"));

        assertTrue(result.delivered());
        verify(expoPushClient).send(eq(VALID_EXPO_TOKEN), anyString(), anyString(), any());
    }

    @Test
    void failsEarlyWhenTitleOrBodyIsBlank() {
        var dispatcher = new PushDispatcher(expoPushClient);
        var user = User.builder().id(UUID.randomUUID()).email("u@e").pushDeviceToken(VALID_EXPO_TOKEN).build();

        var noTitle = new NotificationPayload(user, NotificationType.SYSTEM, null, "B", Map.of());
        assertFalse(dispatcher.dispatch(noTitle).delivered());

        var noBody = new NotificationPayload(user, NotificationType.SYSTEM, "T", "  ", Map.of());
        assertFalse(dispatcher.dispatch(noBody).delivered());

        verify(expoPushClient, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void recordsFailureWhenExpoReturnsError() {
        when(expoPushClient.send(eq(VALID_EXPO_TOKEN), anyString(), anyString(), any()))
                .thenReturn(ExpoPushClient.Result.error("DeviceNotRegistered", "token expired"));
        var dispatcher = new PushDispatcher(expoPushClient);

        var result = dispatcher.dispatch(payload(VALID_EXPO_TOKEN));

        assertFalse(result.delivered());
        assertTrue(result.failureReason().startsWith("Expo DeviceNotRegistered"));
    }
}
