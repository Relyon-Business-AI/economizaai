package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private NotificationPreferenceService preferenceService;

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").build();
    }

    @Test
    void listReturnsEmptyWhenUserHasNoPreferences() {
        var user = user();
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        var result = preferenceService.list(user);

        assertTrue(result.isEmpty());
    }

    @Test
    void listMapsEveryPreferenceToResponse() {
        var user = user();
        var promo = NotificationPreference.builder().user(user)
                .type(NotificationType.PROMO_PERSONAL).channel(NotificationChannel.PUSH).build();
        var priceDrop = NotificationPreference.builder().user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.EMAIL).build();
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of(promo, priceDrop));

        var result = preferenceService.list(user);

        assertEquals(2, result.size());
        assertEquals(NotificationType.PROMO_PERSONAL, result.get(0).type());
        assertEquals(NotificationChannel.PUSH, result.get(0).channel());
        assertEquals(NotificationType.PRICE_DROP, result.get(1).type());
        assertEquals(NotificationChannel.EMAIL, result.get(1).channel());
    }

    @Test
    void listHidesTheRetiredToggles() {
        var user = user();
        var retired = NotificationPreference.builder().user(user)
                .type(NotificationType.PROMO_COMMUNITY).channel(NotificationChannel.PUSH).build();
        var alsoRetired = NotificationPreference.builder().user(user)
                .type(NotificationType.CHEAPER_MARKET).channel(NotificationChannel.PUSH).build();
        var live = NotificationPreference.builder().user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.EMAIL).build();
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of(retired, alsoRetired, live));

        var result = preferenceService.list(user);

        assertEquals(1, result.size());
        assertEquals(NotificationType.PRICE_DROP, result.get(0).type());
    }

    @Test
    void updateCreatesPreferenceRowForType() {
        var user = user();
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PRICE_DROP, NotificationChannel.EMAIL)));
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        preferenceService.update(user, request);

        verify(preferenceRepository).deleteByUserIdAndType(user.getId(), NotificationType.PRICE_DROP);
        var captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(user, saved.getUser());
        assertEquals(NotificationType.PRICE_DROP, saved.getType());
        assertEquals(NotificationChannel.EMAIL, saved.getChannel());
    }

    @Test
    void updateReplacesTheWholeChannelSetForAType() {
        var user = user();
        // user mutes the type → request carries a single NONE for it
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PROMO_PERSONAL, NotificationChannel.NONE)));
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                NotificationPreference.builder().user(user)
                        .type(NotificationType.PROMO_PERSONAL).channel(NotificationChannel.NONE).build()));

        var result = preferenceService.update(user, request);

        verify(preferenceRepository).deleteByUserIdAndType(user.getId(), NotificationType.PROMO_PERSONAL);
        var captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        assertEquals(NotificationChannel.NONE, captor.getValue().getChannel());
        assertEquals(1, result.size());
    }

    @Test
    void updatePersistsMultipleChannelsPerTypeAndAcrossTypes() {
        var user = user();
        // multi-channel for one type (push + email) + another type
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PROMO_PERSONAL, NotificationChannel.PUSH),
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PROMO_PERSONAL, NotificationChannel.EMAIL),
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PRICE_DROP, NotificationChannel.EMAIL)));
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        preferenceService.update(user, request);

        verify(preferenceRepository).deleteByUserIdAndType(user.getId(), NotificationType.PROMO_PERSONAL);
        verify(preferenceRepository).deleteByUserIdAndType(user.getId(), NotificationType.PRICE_DROP);
        verify(preferenceRepository, times(3)).save(any()); // push+email for promo, email for price_drop
    }

    /** The row as the database has it now — newer than the request principal (tier changed meanwhile). */
    private User storedVersionOf(User principal) {
        var stored = User.builder().id(principal.getId()).email(principal.getEmail())
                .subscriptionTier(SubscriptionTier.PRO)
                .pushDeviceToken(principal.getPushDeviceToken())
                .digestSendHour(principal.getDigestSendHour())
                .build();
        when(userRepository.findById(principal.getId())).thenReturn(Optional.of(stored));
        return stored;
    }

    @Test
    void updatePushTokenStoresTrimmableNonBlankToken() {
        var principal = user();
        var stored = storedVersionOf(principal);

        preferenceService.updatePushToken(principal, new UpdatePushTokenRequest("ExponentPushToken[abc]"));

        assertEquals("ExponentPushToken[abc]", stored.getPushDeviceToken());
        assertNotNull(stored.getPushTokenUpdatedAt());
        verify(userRepository).save(stored);
    }

    @Test
    void updatePushTokenSavesTheFreshRowNotTheStaleRequestPrincipal() {
        var principal = user();   // snapshot from the start of the request: still FREE
        var stored = storedVersionOf(principal);

        preferenceService.updatePushToken(principal, new UpdatePushTokenRequest("ExponentPushToken[abc]"));

        verify(userRepository, never()).save(principal);
        assertEquals(SubscriptionTier.PRO, stored.getSubscriptionTier());
    }

    @Test
    void updatePushTokenClearsTokenWhenNull() {
        var principal = user();
        principal.setPushDeviceToken("old-token");
        var stored = storedVersionOf(principal);

        preferenceService.updatePushToken(principal, new UpdatePushTokenRequest(null));

        assertNull(stored.getPushDeviceToken());
        assertNotNull(stored.getPushTokenUpdatedAt());
        verify(userRepository).save(stored);
    }

    @Test
    void updateDigestPreferencesPersistsFrequencyAndSendHour() {
        var principal = user();
        var stored = storedVersionOf(principal);

        var result = preferenceService.updateDigestPreferences(principal,
                new UpdateDigestPreferencesRequest(DigestFrequency.WEEKLY, 8));

        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequency());
        assertEquals(8, stored.getDigestSendHour());
        assertEquals(DigestFrequency.WEEKLY, result.frequency());
        assertEquals(8, result.sendHour());
        verify(userRepository).save(stored);
        verify(userRepository, never()).save(principal);
    }

    @Test
    void updateDigestPreferencesClearsSendHourOverrideWhenNull() {
        var principal = user();
        principal.setDigestSendHour(9);
        var stored = storedVersionOf(principal);

        var result = preferenceService.updateDigestPreferences(principal,
                new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, null));

        assertNull(stored.getDigestSendHour());
        assertNull(result.sendHour());
        assertEquals(DigestFrequency.DAILY, result.frequency());
        verify(userRepository).save(stored);
    }

    @Test
    void getDigestPreferencesReturnsCurrentValues() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.OFF);
        user.setDigestSendHour(20);

        var result = preferenceService.getDigestPreferences(user);

        assertEquals(DigestFrequency.OFF, result.frequency());
        assertEquals(20, result.sendHour());
    }

    @Test
    void updatePushTokenClearsTokenWhenBlank() {
        var principal = user();
        principal.setPushDeviceToken("old-token");
        var stored = storedVersionOf(principal);

        preferenceService.updatePushToken(principal, new UpdatePushTokenRequest("   "));

        assertNull(stored.getPushDeviceToken());
        verify(userRepository).save(stored);
    }
}
