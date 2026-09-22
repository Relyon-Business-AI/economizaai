package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
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
    void updateCreatesNewPreferenceWhenNoneExists() {
        var user = user();
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PRICE_DROP, NotificationChannel.EMAIL)));
        when(preferenceRepository.findByUserIdAndType(user.getId(), NotificationType.PRICE_DROP))
                .thenReturn(Optional.empty());
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        preferenceService.update(user, request);

        var captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(user, saved.getUser());
        assertEquals(NotificationType.PRICE_DROP, saved.getType());
        assertEquals(NotificationChannel.EMAIL, saved.getChannel());
    }

    @Test
    void updateMutatesChannelOnExistingPreference() {
        var user = user();
        var existing = NotificationPreference.builder().user(user)
                .type(NotificationType.PROMO_PERSONAL).channel(NotificationChannel.PUSH).build();
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PROMO_PERSONAL, NotificationChannel.NONE)));
        when(preferenceRepository.findByUserIdAndType(user.getId(), NotificationType.PROMO_PERSONAL))
                .thenReturn(Optional.of(existing));
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of(existing));

        var result = preferenceService.update(user, request);

        assertEquals(NotificationChannel.NONE, existing.getChannel());
        verify(preferenceRepository).save(existing);
        assertEquals(1, result.size());
        assertEquals(NotificationChannel.NONE, result.get(0).channel());
    }

    @Test
    void updatePersistsEveryPreferenceInRequest() {
        var user = user();
        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PROMO_PERSONAL, NotificationChannel.PUSH),
                new UpdateNotificationPreferencesRequest.Preference(
                        NotificationType.PRICE_DROP, NotificationChannel.EMAIL)));
        when(preferenceRepository.findByUserIdAndType(any(), any())).thenReturn(Optional.empty());
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        preferenceService.update(user, request);

        verify(preferenceRepository, times(2)).save(any());
    }

    @Test
    void updatePushTokenStoresTrimmableNonBlankToken() {
        var user = user();
        var request = new UpdatePushTokenRequest("ExponentPushToken[abc]");

        preferenceService.updatePushToken(user, request);

        assertEquals("ExponentPushToken[abc]", user.getPushDeviceToken());
        assertNotNull(user.getPushTokenUpdatedAt());
        verify(userRepository).save(user);
    }

    @Test
    void updatePushTokenClearsTokenWhenNull() {
        var user = user();
        user.setPushDeviceToken("old-token");
        var request = new UpdatePushTokenRequest(null);

        preferenceService.updatePushToken(user, request);

        assertNull(user.getPushDeviceToken());
        assertNotNull(user.getPushTokenUpdatedAt());
        verify(userRepository).save(user);
    }

    @Test
    void updateDigestPreferencesPersistsFrequencyAndSendHour() {
        var user = user();
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.WEEKLY, 8);

        var result = preferenceService.updateDigestPreferences(user, request);

        assertEquals(DigestFrequency.WEEKLY, user.getDigestFrequency());
        assertEquals(8, user.getDigestSendHour());
        assertEquals(DigestFrequency.WEEKLY, result.frequency());
        assertEquals(8, result.sendHour());
        verify(userRepository).save(user);
    }

    @Test
    void updateDigestPreferencesClearsSendHourOverrideWhenNull() {
        var user = user();
        user.setDigestSendHour(9);
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, null);

        var result = preferenceService.updateDigestPreferences(user, request);

        assertNull(user.getDigestSendHour());
        assertNull(result.sendHour());
        assertEquals(DigestFrequency.DAILY, result.frequency());
        verify(userRepository).save(user);
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
        var user = user();
        user.setPushDeviceToken("old-token");
        var request = new UpdatePushTokenRequest("   ");

        preferenceService.updatePushToken(user, request);

        assertNull(user.getPushDeviceToken());
        verify(userRepository).save(user);
    }
}
