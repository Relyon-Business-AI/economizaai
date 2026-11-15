package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationNoticeOptionRepository;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationNoticeOptionRepository optionRepository;
    @Mock private TwilioMessageClient twilioMessageClient;

    private NotificationPreferenceService preferenceService;

    /** What the option table holds — the mocked repository reads and writes this list. */
    private final List<NotificationNoticeOption> storedOptions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(optionRepository.findAllByUserId(any())).thenAnswer(invocation -> List.copyOf(storedOptions));
        lenient().when(optionRepository.findAllByUserIdAndNotice(any(), any())).thenAnswer(invocation ->
                storedOptions.stream()
                        .filter(option -> option.getNotice() == invocation.getArgument(1))
                        .toList());
        lenient().when(optionRepository.save(any())).thenAnswer(invocation -> {
            NotificationNoticeOption option = invocation.getArgument(0);
            if (!storedOptions.contains(option)) storedOptions.add(option);
            return option;
        });
        var optionStore = new NoticeOptionStore(optionRepository, new WhatsAppChannelPolicy(twilioMessageClient));
        preferenceService = new NotificationPreferenceService(preferenceRepository, userRepository, optionStore);
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("maria@example.com").role(Role.USER).build();
    }

    private void stored(NotificationNotice notice, NotificationChannel channel, boolean enabled) {
        storedOptions.add(NotificationNoticeOption.builder().notice(notice).channel(channel).enabled(enabled).build());
    }

    private boolean isOn(User user, NotificationNotice notice, NotificationChannel channel) {
        return new NoticeOptions(user, storedOptions).isEnabled(notice, channel);
    }

    private static UpdateNotificationPreferencesRequest legacyWrite(NotificationType type,
                                                                    NotificationChannel... channels) {
        return new UpdateNotificationPreferencesRequest(Arrays.stream(channels)
                .map(channel -> new UpdateNotificationPreferencesRequest.Preference(type, channel))
                .toList());
    }

    private static NotificationChannel channelOf(List<NotificationPreferenceResponse> preferences,
                                                 NotificationType type) {
        return preferences.stream().filter(preference -> preference.type() == type)
                .map(NotificationPreferenceResponse::channel).findFirst().orElseThrow();
    }

    // ---- legacy read: one channel per type, answered from the notice options ----

    @Test
    void listShowsPushForEveryNoticeTypeOfAUserWhoNeverConfiguredAnything() {
        var result = preferenceService.list(user());

        var listedTypes = result.stream().map(NotificationPreferenceResponse::type).toList();
        assertEquals(List.of(NotificationType.PROMO_PERSONAL, NotificationType.PRICE_DROP, NotificationType.STOCKOUT,
                NotificationType.BUDGET, NotificationType.DEALS_DIGEST, NotificationType.SYSTEM,
                NotificationType.RECEIPT_RECOVERED, NotificationType.RECEIPT_READY, NotificationType.RECEIPT_FAILED),
                listedTypes);
        result.forEach(preference -> assertEquals(NotificationChannel.PUSH, preference.channel()));
    }

    @Test
    void listReadsPushThenEmailThenNone() {
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH, false);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);
        stored(NotificationNotice.STOCKOUT, NotificationChannel.PUSH, false);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL, true);

        var result = preferenceService.list(user());

        assertEquals(NotificationChannel.EMAIL, channelOf(result, NotificationType.PRICE_DROP));
        assertEquals(NotificationChannel.EMAIL, channelOf(result, NotificationType.BUDGET));
        assertEquals(NotificationChannel.NONE, channelOf(result, NotificationType.STOCKOUT));
        assertEquals(NotificationChannel.PUSH, channelOf(result, NotificationType.DEALS_DIGEST));
    }

    @Test
    void listReadsNoneForAnAccountNoticeWithPushOffAndEmailNeverSwitchedOn() {
        stored(NotificationNotice.ACCOUNT, NotificationChannel.PUSH, false);

        assertEquals(NotificationChannel.NONE, channelOf(preferenceService.list(user()), NotificationType.SYSTEM));
    }

    @Test
    void listNeverShowsTheWeeklyReportTypeOlderAppsDoNotKnow() {
        stored(NotificationNotice.WEEKLY_REPORT, NotificationChannel.EMAIL, true);

        assertTrue(preferenceService.list(user()).stream()
                .noneMatch(preference -> preference.type() == NotificationType.WEEKLY_REPORT));
    }

    @Test
    void listKeepsTheStoredRowsOfTypesOutsideTheNoticeModelAndHidesTheRetiredOnes() {
        var user = user();
        when(preferenceRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                NotificationPreference.builder().type(NotificationType.PROMO_COMMUNITY).channel(NotificationChannel.PUSH).build(),
                NotificationPreference.builder().type(NotificationType.DIGEST).channel(NotificationChannel.EMAIL).build(),
                // a stale per-type row of a notice type is no longer what decides
                NotificationPreference.builder().type(NotificationType.STOCKOUT).channel(NotificationChannel.NONE).build()));

        var result = preferenceService.list(user);

        assertEquals(NotificationChannel.EMAIL, channelOf(result, NotificationType.DIGEST));
        assertEquals(NotificationChannel.PUSH, channelOf(result, NotificationType.STOCKOUT));
        assertTrue(result.stream().noneMatch(preference -> preference.type() == NotificationType.PROMO_COMMUNITY));
        assertEquals(1, result.stream().filter(preference -> preference.type() == NotificationType.STOCKOUT).count());
    }

    // ---- legacy write: translated to the notice options ----

    @Test
    void legacyPushWriteTurnsPushOnAndEmailOff() {
        var principal = user();
        storedVersionOf(principal);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH, false);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);

        var result = preferenceService.update(principal, legacyWrite(NotificationType.PRICE_DROP, NotificationChannel.PUSH));

        assertTrue(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertFalse(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
        assertEquals(NotificationChannel.PUSH, channelOf(result, NotificationType.PRICE_DROP));
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void legacyEmailWriteTurnsEmailOnAndPushOff() {
        var principal = user();
        storedVersionOf(principal);

        var result = preferenceService.update(principal, legacyWrite(NotificationType.PRICE_DROP, NotificationChannel.EMAIL));

        assertFalse(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertTrue(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
        assertEquals(NotificationChannel.EMAIL, channelOf(result, NotificationType.PRICE_DROP));
    }

    @Test
    void legacyEmailWriteOnANoticeWithoutEmailKeepsPushOnInsteadOfSilencingIt() {
        var principal = user();
        storedVersionOf(principal);
        stored(NotificationNotice.PERSONAL_OFFERS, NotificationChannel.PUSH, false);

        var result = preferenceService.update(principal,
                legacyWrite(NotificationType.PROMO_PERSONAL, NotificationChannel.EMAIL));

        assertTrue(isOn(principal, NotificationNotice.PERSONAL_OFFERS, NotificationChannel.PUSH));
        assertEquals(NotificationChannel.PUSH, channelOf(result, NotificationType.PROMO_PERSONAL));
    }

    @Test
    void legacyNoneWriteTurnsEveryOptionOff() {
        var principal = user();
        storedVersionOf(principal);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true);

        var result = preferenceService.update(principal, legacyWrite(NotificationType.PRICE_DROP, NotificationChannel.NONE));

        assertFalse(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertFalse(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
        assertFalse(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP));
        assertEquals(NotificationChannel.NONE, channelOf(result, NotificationType.PRICE_DROP));
    }

    @Test
    void legacyNoneWriteOnTheAccountNoticeTurnsPushAndEmailOff() {
        var principal = user();
        storedVersionOf(principal);

        var result = preferenceService.update(principal, legacyWrite(NotificationType.SYSTEM, NotificationChannel.NONE));

        assertFalse(isOn(principal, NotificationNotice.ACCOUNT, NotificationChannel.PUSH));
        assertFalse(isOn(principal, NotificationNotice.ACCOUNT, NotificationChannel.EMAIL));
        assertEquals(NotificationChannel.NONE, channelOf(result, NotificationType.SYSTEM));
    }

    @Test
    void legacyWriteWithBothChannelsTurnsBothOn() {
        var principal = user();
        storedVersionOf(principal);

        preferenceService.update(principal,
                legacyWrite(NotificationType.PRICE_DROP, NotificationChannel.PUSH, NotificationChannel.EMAIL));

        assertTrue(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertTrue(isOn(principal, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
    }

    @Test
    void legacyWriteLeavesTheNoticesItDoesNotMentionUntouched() {
        var principal = user();
        storedVersionOf(principal);
        stored(NotificationNotice.STOCKOUT, NotificationChannel.PUSH, false);

        preferenceService.update(principal, legacyWrite(NotificationType.PROMO_PERSONAL, NotificationChannel.PUSH));

        assertFalse(isOn(principal, NotificationNotice.STOCKOUT, NotificationChannel.PUSH));
    }

    @Test
    void legacyMuteOfTheDealsDigestTypeSwitchesTheDigestOff() {
        var principal = user();
        var stored = storedVersionOf(principal);
        stored.setDigestFrequency(DigestFrequency.WEEKLY);

        preferenceService.update(principal, legacyWrite(NotificationType.DEALS_DIGEST, NotificationChannel.NONE));

        assertEquals(DigestFrequency.OFF, stored.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequencyChoice());
        verify(userRepository).save(stored);
    }

    @Test
    void legacyWriteOfATypeOutsideTheNoticeModelStillReplacesItsRows() {
        var principal = user();
        var stored = storedVersionOf(principal);

        preferenceService.update(principal, legacyWrite(NotificationType.DIGEST, NotificationChannel.EMAIL));

        verify(preferenceRepository).deleteByUserIdAndType(principal.getId(), NotificationType.DIGEST);
        var captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        assertEquals(stored, captor.getValue().getUser());
        assertEquals(NotificationType.DIGEST, captor.getValue().getType());
        assertEquals(NotificationChannel.EMAIL, captor.getValue().getChannel());
        assertTrue(storedOptions.isEmpty());
    }

    @Test
    void legacyWriteIgnoresTheRetiredToggles() {
        var principal = user();
        storedVersionOf(principal);

        preferenceService.update(principal, legacyWrite(NotificationType.PROMO_COMMUNITY, NotificationChannel.NONE));

        verify(preferenceRepository, never()).save(any());
        assertTrue(storedOptions.isEmpty());
    }

    // ---- legacy digest endpoints ----

    @Test
    void legacyDigestOffTurnsEveryDigestOptionOffAndRemembersTheFrequency() {
        var principal = user();
        var stored = storedVersionOf(principal);
        stored.setDigestFrequency(DigestFrequency.WEEKLY);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL, true);

        var result = preferenceService.updateDigestPreferences(principal,
                new UpdateDigestPreferencesRequest(DigestFrequency.OFF, null));

        assertEquals(DigestFrequency.OFF, result.frequency());
        assertEquals(DigestFrequency.OFF, stored.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequencyChoice());
        assertFalse(isOn(stored, NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH));
        assertFalse(isOn(stored, NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL));
    }

    @Test
    void legacyDigestBackOnSwitchesPushOnWhenNothingWasOn() {
        var principal = user();
        var stored = storedVersionOf(principal);
        stored.setDigestFrequency(DigestFrequency.OFF);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false);

        var result = preferenceService.updateDigestPreferences(principal,
                new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, null));

        assertEquals(DigestFrequency.DAILY, result.frequency());
        assertTrue(isOn(stored, NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH));
    }

    @Test
    void legacyDigestFrequencyChangeKeepsTheOptionsThatWereOn() {
        var principal = user();
        var stored = storedVersionOf(principal);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL, true);

        preferenceService.updateDigestPreferences(principal,
                new UpdateDigestPreferencesRequest(DigestFrequency.WEEKLY, null));

        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequency());
        assertFalse(isOn(stored, NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH));
        assertTrue(isOn(stored, NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL));
    }

    /** The row as the database has it now — newer than the request principal (tier changed meanwhile). */
    private User storedVersionOf(User principal) {
        var stored = User.builder().id(principal.getId()).email(principal.getEmail())
                .subscriptionTier(SubscriptionTier.PRO).role(Role.USER)
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
