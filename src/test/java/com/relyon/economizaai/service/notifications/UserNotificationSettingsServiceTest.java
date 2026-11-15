package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateNotificationSettingsRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationSettingsRequest.OptionChange;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse;
import com.relyon.economizaai.exception.InvalidNotificationSettingsException;
import com.relyon.economizaai.exception.WhatsAppNotConnectedException;
import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.NotificationNoticeOptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserNotificationSettingsServiceTest {

    private static final String CONFIRM_LINK = "https://wa.me/14063448189?text=CONFIRMAR-tok";

    @Mock private UserRepository userRepository;
    @Mock private NotificationNoticeOptionRepository optionRepository;
    @Mock private TwilioMessageClient twilioMessageClient;
    @Mock private WhatsAppOptInService whatsAppOptInService;

    private UserNotificationSettingsService settingsService;

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
        lenient().when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        var whatsAppPolicy = new WhatsAppChannelPolicy(twilioMessageClient);
        settingsService = new UserNotificationSettingsService(userRepository,
                new NoticeOptionStore(optionRepository, whatsAppPolicy), whatsAppPolicy, whatsAppOptInService);
    }

    /** The request principal (a stale snapshot) plus the fresh row the service must work on. */
    private User storedUser(Role role) {
        var stored = User.builder().id(UUID.randomUUID()).email("maria@economizaai.app").role(role)
                .subscriptionTier(SubscriptionTier.PRO).build();
        when(userRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        return stored;
    }

    private static User principalOf(User stored) {
        return User.builder().id(stored.getId()).email(stored.getEmail()).role(stored.getRole())
                .subscriptionTier(SubscriptionTier.FREE).build();
    }

    private User connectedAdmin() {
        var admin = storedUser(Role.ADMIN);
        admin.setWhatsAppOptIn(true);
        admin.setWhatsAppNumber("+5551997513229");
        lenient().when(whatsAppOptInService.maskedWhatsAppNumber(admin)).thenReturn("****3229");
        return admin;
    }

    private void stored(NotificationNotice notice, NotificationChannel channel, boolean enabled) {
        storedOptions.add(NotificationNoticeOption.builder().notice(notice).channel(channel).enabled(enabled).build());
    }

    private static UpdateNotificationSettingsRequest change(String key, String channel, Boolean enabled) {
        return new UpdateNotificationSettingsRequest(List.of(new OptionChange(key, channel, enabled)), null);
    }

    private static NotificationSettingsResponse.Option optionOf(NotificationSettingsResponse settings,
                                                                NotificationNotice notice,
                                                                NotificationChannel channel) {
        return settings.notices().stream().filter(entry -> entry.key() == notice)
                .flatMap(entry -> entry.options().stream())
                .filter(option -> option.channel() == channel)
                .findFirst().orElseThrow();
    }

    private static List<NotificationChannel> channelsOf(NotificationSettingsResponse settings,
                                                        NotificationNotice notice) {
        return settings.notices().stream().filter(entry -> entry.key() == notice)
                .flatMap(entry -> entry.options().stream())
                .map(NotificationSettingsResponse.Option::channel)
                .toList();
    }

    // ---- reading ----

    @Test
    void defaultsForARegularUser() {
        var stored = storedUser(Role.USER);

        var settings = settingsService.openSettings(principalOf(stored));

        assertEquals("maria@economizaai.app", settings.email());
        assertEquals(DigestFrequency.DAILY, settings.digestFrequency());
        assertEquals(new NotificationSettingsResponse.WhatsApp(false, false, null, null), settings.whatsApp());
        assertEquals(List.of(NotificationNotice.values()),
                settings.notices().stream().map(NotificationSettingsResponse.Notice::key).toList());
        assertEquals(List.of(NotificationChannel.PUSH), channelsOf(settings, NotificationNotice.RECEIPT_READY));
        assertEquals(List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                channelsOf(settings, NotificationNotice.PRICE_ALERTS));
        assertEquals(List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                channelsOf(settings, NotificationNotice.DEALS_DIGEST));
        assertEquals(List.of(NotificationChannel.EMAIL), channelsOf(settings, NotificationNotice.WEEKLY_REPORT));
        assertEquals(new NotificationSettingsResponse.Option(NotificationChannel.PUSH, true, false),
                optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertEquals(new NotificationSettingsResponse.Option(NotificationChannel.EMAIL, false, false),
                optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
        assertEquals(new NotificationSettingsResponse.Option(NotificationChannel.EMAIL, true, true),
                optionOf(settings, NotificationNotice.ACCOUNT, NotificationChannel.EMAIL));
        assertEquals(new NotificationSettingsResponse.Option(NotificationChannel.PUSH, true, false),
                optionOf(settings, NotificationNotice.ACCOUNT, NotificationChannel.PUSH));
        verify(whatsAppOptInService, never()).issueConfirmLink(any());
    }

    @Test
    void whatsAppIsNeverListedForANonAdminEvenWhenStoredOn() {
        var stored = storedUser(Role.USER);
        stored.setWhatsAppOptIn(true);
        stored.setWhatsAppNumber("+5551997513229");
        stored.setWhatsAppWeeklyReportEnabled(true);
        stored(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true);

        var settings = settingsService.openSettings(principalOf(stored));

        assertFalse(settings.whatsApp().available());
        assertFalse(settings.whatsApp().connected());
        assertNull(settings.whatsApp().maskedNumber());
        assertNull(settings.whatsApp().confirmLink());
        settings.notices().forEach(notice -> assertTrue(notice.options().stream()
                .noneMatch(option -> option.channel() == NotificationChannel.WHATSAPP), notice.key().name()));
    }

    @Test
    void whatsAppIsNotAvailableForAnAdminWithoutTheTwilioSender() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(false);
        var admin = connectedAdmin();

        var settings = settingsService.openSettings(principalOf(admin));

        assertFalse(settings.whatsApp().available());
        assertEquals(List.of(NotificationChannel.EMAIL), channelsOf(settings, NotificationNotice.WEEKLY_REPORT));
    }

    @Test
    void adminNotYetConnectedGetsTheConfirmLinkAndWhatsAppOptionsOff() {
        var admin = storedUser(Role.ADMIN);
        when(whatsAppOptInService.issueConfirmLink(admin)).thenReturn(CONFIRM_LINK);

        var settings = settingsService.openSettings(principalOf(admin));

        assertEquals(new NotificationSettingsResponse.WhatsApp(true, false, null, CONFIRM_LINK), settings.whatsApp());
        assertEquals(List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL, NotificationChannel.WHATSAPP),
                channelsOf(settings, NotificationNotice.PRICE_ALERTS));
        assertFalse(optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP).enabled());
    }

    @Test
    void connectedAdminSeesTheMaskedNumberAndNoConfirmLink() {
        var admin = connectedAdmin();
        admin.setWhatsAppWeeklyReportEnabled(true);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.WHATSAPP, true);

        var settings = settingsService.openSettings(principalOf(admin));

        assertEquals(new NotificationSettingsResponse.WhatsApp(true, true, "****3229", null), settings.whatsApp());
        assertTrue(optionOf(settings, NotificationNotice.DEALS_DIGEST, NotificationChannel.WHATSAPP).enabled());
        assertTrue(optionOf(settings, NotificationNotice.WEEKLY_REPORT, NotificationChannel.WHATSAPP).enabled());
        assertFalse(optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP).enabled());
        verify(whatsAppOptInService, never()).issueConfirmLink(any());
    }

    @Test
    void digestFrequencyKeepsTheLastChoiceWhileTheDigestIsOff() {
        var stored = storedUser(Role.USER);
        stored.setDigestFrequency(DigestFrequency.OFF);
        stored.setDigestFrequencyChoice(DigestFrequency.WEEKLY);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false);

        var settings = settingsService.openSettings(principalOf(stored));

        assertEquals(DigestFrequency.WEEKLY, settings.digestFrequency());
        assertFalse(optionOf(settings, NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH).enabled());
    }

    // ---- updating ----

    @Test
    void updateChangesOnlyTheOptionSentAndSavesTheFreshRow() {
        var stored = storedUser(Role.USER);
        var principal = principalOf(stored);
        stored(NotificationNotice.STOCKOUT, NotificationChannel.PUSH, false);

        var settings = settingsService.update(principal, change("PRICE_ALERTS", "EMAIL", true));

        assertTrue(optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL).enabled());
        assertTrue(optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH).enabled());
        assertFalse(optionOf(settings, NotificationNotice.STOCKOUT, NotificationChannel.PUSH).enabled());
        verify(userRepository).save(stored);
        verify(userRepository, never()).save(principal);
    }

    @Test
    void updateWithAnEmptyBodyChangesNothing() {
        var stored = storedUser(Role.USER);

        var settings = settingsService.update(principalOf(stored), new UpdateNotificationSettingsRequest(null, null));

        assertTrue(storedOptions.isEmpty());
        assertEquals(DigestFrequency.DAILY, settings.digestFrequency());
    }

    @Test
    void switchingEveryDigestOptionOffStopsTheDigestButKeepsTheFrequency() {
        var stored = storedUser(Role.USER);
        stored.setDigestFrequency(DigestFrequency.WEEKLY);

        var settings = settingsService.update(principalOf(stored), change("DEALS_DIGEST", "PUSH", false));

        assertEquals(DigestFrequency.OFF, stored.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, settings.digestFrequency());
    }

    @Test
    void switchingADigestOptionBackOnResumesAtTheKeptFrequency() {
        var stored = storedUser(Role.USER);
        stored.setDigestFrequency(DigestFrequency.OFF);
        stored.setDigestFrequencyChoice(DigestFrequency.WEEKLY);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false);

        settingsService.update(principalOf(stored), change("DEALS_DIGEST", "EMAIL", true));

        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequency());
    }

    @Test
    void digestFrequencyAloneChangesTheFrequencyOfARunningDigest() {
        var stored = storedUser(Role.USER);

        var settings = settingsService.update(principalOf(stored),
                new UpdateNotificationSettingsRequest(null, "WEEKLY"));

        assertEquals(DigestFrequency.WEEKLY, stored.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, settings.digestFrequency());
    }

    @Test
    void digestFrequencyChosenWhileOffIsRememberedWithoutSwitchingTheDigestOn() {
        var stored = storedUser(Role.USER);
        stored.setDigestFrequency(DigestFrequency.OFF);
        stored(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false);

        var settings = settingsService.update(principalOf(stored),
                new UpdateNotificationSettingsRequest(null, "WEEKLY"));

        assertEquals(DigestFrequency.OFF, stored.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, settings.digestFrequency());
    }

    @Test
    void connectedAdminCanSwitchWhatsAppOn() {
        var admin = connectedAdmin();

        var settings = settingsService.update(principalOf(admin), new UpdateNotificationSettingsRequest(List.of(
                new OptionChange("PRICE_ALERTS", "WHATSAPP", true),
                new OptionChange("WEEKLY_REPORT", "WHATSAPP", true)), null));

        assertTrue(optionOf(settings, NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP).enabled());
        assertTrue(optionOf(settings, NotificationNotice.WEEKLY_REPORT, NotificationChannel.WHATSAPP).enabled());
        assertTrue(admin.isWhatsAppWeeklyReportEnabled());
    }

    @Test
    void switchingTheLockedAccountEmailOnIsAcceptedAsANoOp() {
        var stored = storedUser(Role.USER);

        var settings = settingsService.update(principalOf(stored), change("ACCOUNT", "EMAIL", true));

        assertTrue(optionOf(settings, NotificationNotice.ACCOUNT, NotificationChannel.EMAIL).enabled());
        assertTrue(storedOptions.isEmpty());
    }

    // ---- validation: nothing is applied when any part of the request is invalid ----

    private void assertRejected(User stored, UpdateNotificationSettingsRequest request, String messageKey) {
        var rejection = assertThrows(InvalidNotificationSettingsException.class,
                () -> settingsService.update(principalOf(stored), request));
        assertEquals(messageKey, rejection.getMessageKey());
        assertTrue(storedOptions.isEmpty());
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsSwitchingTheLockedAccountEmailOff() {
        assertRejected(storedUser(Role.USER), change("ACCOUNT", "EMAIL", false),
                "notification.settings.option.locked");
    }

    @Test
    void rejectsAChannelTheNoticeDoesNotOffer() {
        assertRejected(storedUser(Role.USER), change("STOCKOUT", "EMAIL", true),
                "notification.settings.channel.not-offered");
        assertRejected(storedUser(Role.ADMIN), change("WEEKLY_REPORT", "PUSH", true),
                "notification.settings.channel.not-offered");
    }

    @Test
    void rejectsUnknownNoticeKeyAndUnknownChannel() {
        assertRejected(storedUser(Role.USER), change("PROMO_PERSONAL", "PUSH", true),
                "notification.settings.notice.unknown");
        assertRejected(storedUser(Role.USER), change(null, "PUSH", true),
                "notification.settings.notice.unknown");
        assertRejected(storedUser(Role.USER), change("STOCKOUT", "TELEGRAM", true),
                "notification.settings.channel.unknown");
        assertRejected(storedUser(Role.USER), change("STOCKOUT", null, true),
                "notification.settings.channel.unknown");
    }

    @Test
    void rejectsAChangeWithoutTheEnabledValue() {
        assertRejected(storedUser(Role.USER), change("STOCKOUT", "PUSH", null),
                "notification.settings.option.enabled-required");
    }

    @Test
    void rejectsWhatsAppForAUserItIsNotAvailableToEvenToSwitchItOff() {
        var optedInRegularUser = storedUser(Role.USER);
        optedInRegularUser.setWhatsAppOptIn(true);
        optedInRegularUser.setWhatsAppNumber("+5551997513229");

        assertRejected(optedInRegularUser, change("PRICE_ALERTS", "WHATSAPP", true),
                "notification.settings.whatsapp.unavailable");
        assertRejected(optedInRegularUser, change("PRICE_ALERTS", "WHATSAPP", false),
                "notification.settings.whatsapp.unavailable");
    }

    @Test
    void rejectsWhatsAppOnForAnAdminWhoHasNotConnectedYet() {
        var admin = storedUser(Role.ADMIN);

        assertThrows(WhatsAppNotConnectedException.class,
                () -> settingsService.update(principalOf(admin), change("PRICE_ALERTS", "WHATSAPP", true)));

        assertTrue(storedOptions.isEmpty());
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsAnInvalidDigestFrequencyIncludingOff() {
        assertRejected(storedUser(Role.USER), new UpdateNotificationSettingsRequest(null, "OFF"),
                "notification.settings.digest-frequency.invalid");
        assertRejected(storedUser(Role.USER), new UpdateNotificationSettingsRequest(null, "MONTHLY"),
                "notification.settings.digest-frequency.invalid");
    }

    @Test
    void oneInvalidChangeRejectsTheWholeRequest() {
        var stored = storedUser(Role.USER);
        var request = new UpdateNotificationSettingsRequest(List.of(
                new OptionChange("PRICE_ALERTS", "EMAIL", true),
                new OptionChange("ACCOUNT", "EMAIL", false)), "WEEKLY");

        assertRejected(stored, request, "notification.settings.option.locked");
        assertEquals(DigestFrequency.DAILY, stored.getDigestFrequency());
    }
}
