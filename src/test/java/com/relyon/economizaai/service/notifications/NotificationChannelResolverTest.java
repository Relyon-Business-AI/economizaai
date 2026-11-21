package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.NotificationNoticeOptionRepository;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationChannelResolverTest {

    @Mock private NotificationNoticeOptionRepository optionRepository;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private TwilioMessageClient twilioMessageClient;

    private NotificationChannelResolver resolver;

    @BeforeEach
    void setUp() {
        var whatsAppPolicy = new WhatsAppChannelPolicy(twilioMessageClient);
        resolver = new NotificationChannelResolver(
                new NoticeOptionStore(optionRepository, whatsAppPolicy), preferenceRepository, whatsAppPolicy);
    }

    private static User.UserBuilder<?, ?> userWithPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e").role(Role.USER).pushDeviceToken("expo-token");
    }

    private static User.UserBuilder<?, ?> userWithoutPushToken() {
        return User.builder().id(UUID.randomUUID()).email("u@e").role(Role.USER);
    }

    private static User connectedAdmin() {
        return userWithPushToken().role(Role.ADMIN).whatsAppOptIn(true).whatsAppNumber("+5551999990000").build();
    }

    private void twilioWhatsAppSenderIsConfigured() {
        lenient().when(twilioMessageClient.isConfigured(true)).thenReturn(true);
    }

    private void stored(NotificationNotice notice, NotificationNoticeOption... options) {
        when(optionRepository.findAllByUserIdAndNotice(any(), eq(notice))).thenReturn(List.of(options));
    }

    private static NotificationNoticeOption option(NotificationNotice notice, NotificationChannel channel,
                                                   boolean enabled) {
        return NotificationNoticeOption.builder().notice(notice).channel(channel).enabled(enabled).build();
    }

    // ---- defaults ----

    @Test
    void neverConfiguredNoticeGoesOutOnPushOnly() {
        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.PRICE_DROP);

        assertFalse(resolution.muted());
        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    @Test
    void neverConfiguredNoticeWithoutPushTokenStillFallsBackToEmail() {
        var resolution = resolver.resolve(userWithoutPushToken().build(), NotificationType.PROMO_PERSONAL);

        assertEquals(Set.of(NotificationChannel.EMAIL), resolution.outbound());
    }

    @Test
    void configuredNoticeWithoutPushTokenDoesNotFallBackToEmail() {
        stored(NotificationNotice.PERSONAL_OFFERS,
                option(NotificationNotice.PERSONAL_OFFERS, NotificationChannel.PUSH, true));

        var resolution = resolver.resolve(userWithoutPushToken().build(), NotificationType.PROMO_PERSONAL);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    @Test
    void everyReceiptTypeFollowsTheReceiptReadyNotice() {
        stored(NotificationNotice.RECEIPT_READY,
                option(NotificationNotice.RECEIPT_READY, NotificationChannel.PUSH, false));
        var user = userWithPushToken().build();

        assertTrue(resolver.resolve(user, NotificationType.RECEIPT_READY).muted());
        assertTrue(resolver.resolve(user, NotificationType.RECEIPT_FAILED).muted());
        assertTrue(resolver.resolve(user, NotificationType.RECEIPT_RECOVERED).muted());
    }

    // ---- per notice, per channel ----

    @Test
    void goesOutOnEveryChannelSwitchedOn() {
        stored(NotificationNotice.PRICE_ALERTS,
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.PRICE_DROP);

        assertEquals(Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL), resolution.outbound());
    }

    @Test
    void emailOnlyWhenPushIsSwitchedOff() {
        stored(NotificationNotice.DEALS_DIGEST,
                option(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false),
                option(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL, true));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.DEALS_DIGEST);

        assertEquals(Set.of(NotificationChannel.EMAIL), resolution.outbound());
    }

    @Test
    void noticeWithNothingOnIsMuted() {
        stored(NotificationNotice.STOCKOUT, option(NotificationNotice.STOCKOUT, NotificationChannel.PUSH, false));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.STOCKOUT);

        assertTrue(resolution.muted());
        assertTrue(resolution.outbound().isEmpty());
    }

    @Test
    void aChannelTheNoticeDoesNotOfferIsNeverUsedEvenIfStored() {
        stored(NotificationNotice.STOCKOUT, option(NotificationNotice.STOCKOUT, NotificationChannel.EMAIL, true));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.STOCKOUT);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    // ---- ACCOUNT e-mail: an option like the others, off until switched on ----

    @Test
    void neverConfiguredAccountNoticeGoesOutOnPushOnly() {
        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.SYSTEM);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    @Test
    void accountNoticeGoesByEmailTooOnceSwitchedOn() {
        stored(NotificationNotice.ACCOUNT, option(NotificationNotice.ACCOUNT, NotificationChannel.EMAIL, true));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.SYSTEM);

        assertEquals(Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL), resolution.outbound());
    }

    @Test
    void accountNoticeWithEveryOptionOffIsMuted() {
        stored(NotificationNotice.ACCOUNT,
                option(NotificationNotice.ACCOUNT, NotificationChannel.PUSH, false),
                option(NotificationNotice.ACCOUNT, NotificationChannel.EMAIL, false));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.SYSTEM);

        assertTrue(resolution.muted());
    }

    @Test
    void neverConfiguredAccountNoticeWithoutPushTokenKeepsTheEmailFallback() {
        var resolution = resolver.resolve(userWithoutPushToken().build(), NotificationType.SYSTEM);

        assertEquals(Set.of(NotificationChannel.EMAIL), resolution.outbound());
    }

    // ---- WhatsApp: admin only, sender configured, opted in ----

    @Test
    void connectedAdminGetsTheWhatsAppLegWhenSwitchedOn() {
        twilioWhatsAppSenderIsConfigured();
        stored(NotificationNotice.PRICE_ALERTS,
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true));

        var resolution = resolver.resolve(connectedAdmin(), NotificationType.PRICE_DROP);

        assertEquals(Set.of(NotificationChannel.PUSH, NotificationChannel.WHATSAPP), resolution.outbound());
    }

    @Test
    void nonAdminNeverGetsWhatsAppWhateverIsStored() {
        twilioWhatsAppSenderIsConfigured();
        stored(NotificationNotice.PRICE_ALERTS,
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true));
        var optedInRegularUser = userWithPushToken().whatsAppOptIn(true).whatsAppNumber("+5551999990000").build();

        var resolution = resolver.resolve(optedInRegularUser, NotificationType.PRICE_DROP);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    @Test
    void adminWithoutTheTwilioSenderGetsNoWhatsApp() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(false);
        stored(NotificationNotice.PRICE_ALERTS,
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true));

        var resolution = resolver.resolve(connectedAdmin(), NotificationType.PRICE_DROP);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    @Test
    void adminWhoDidNotOptInGetsNoWhatsApp() {
        twilioWhatsAppSenderIsConfigured();
        stored(NotificationNotice.PRICE_ALERTS,
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.WHATSAPP, true));
        var adminWithoutOptIn = userWithPushToken().role(Role.ADMIN).build();

        var resolution = resolver.resolve(adminWithoutOptIn, NotificationType.PRICE_DROP);

        assertEquals(Set.of(NotificationChannel.PUSH), resolution.outbound());
    }

    // ---- weekly report ----

    @Test
    void weeklyReportIsMutedByDefault() {
        assertTrue(resolver.resolve(userWithPushToken().build(), NotificationType.WEEKLY_REPORT).muted());
    }

    @Test
    void weeklyReportGoesByEmailWhenSwitchedOnAndNeverByPush() {
        stored(NotificationNotice.WEEKLY_REPORT,
                option(NotificationNotice.WEEKLY_REPORT, NotificationChannel.EMAIL, true));

        var resolution = resolver.resolve(userWithPushToken().build(), NotificationType.WEEKLY_REPORT);

        assertEquals(Set.of(NotificationChannel.EMAIL), resolution.outbound());
    }

    @Test
    void weeklyReportWhatsAppOptionLivesOnTheUserRow() {
        twilioWhatsAppSenderIsConfigured();
        var admin = connectedAdmin();
        admin.setWhatsAppWeeklyReportEnabled(true);

        var resolution = resolver.resolve(admin, NotificationType.WEEKLY_REPORT);

        assertEquals(Set.of(NotificationChannel.WHATSAPP), resolution.outbound());
    }

    @Test
    void weeklyReportWhatsAppToggleOfANonAdminIsNotDelivered() {
        twilioWhatsAppSenderIsConfigured();
        var regularUser = userWithPushToken().whatsAppOptIn(true).whatsAppNumber("+5551999990000").build();
        regularUser.setWhatsAppWeeklyReportEnabled(true);

        assertTrue(resolver.resolve(regularUser, NotificationType.WEEKLY_REPORT).muted());
    }

    // ---- types outside the notice model keep their per-type rows ----

    @Test
    void typeOutsideTheNoticeModelDefaultsToPushWithTokenElseEmail() {
        assertEquals(Set.of(NotificationChannel.PUSH), resolver
                .resolve(userWithPushToken().build(), NotificationType.HOUSEHOLD_MEMBER_JOINED).outbound());
        assertEquals(Set.of(NotificationChannel.EMAIL), resolver
                .resolve(userWithoutPushToken().build(), NotificationType.DIGEST).outbound());
    }

    @Test
    void typeOutsideTheNoticeModelHonoursItsStoredRows() {
        var user = userWithPushToken().build();
        when(preferenceRepository.findAllByUserIdAndType(user.getId(), NotificationType.DIGEST)).thenReturn(List.of(
                NotificationPreference.builder().type(NotificationType.DIGEST).channel(NotificationChannel.NONE).build()));
        when(preferenceRepository.findAllByUserIdAndType(user.getId(), NotificationType.HOUSEHOLD_MEMBER_LEFT))
                .thenReturn(List.of(
                        NotificationPreference.builder().type(NotificationType.HOUSEHOLD_MEMBER_LEFT)
                                .channel(NotificationChannel.INBOX).build(),
                        NotificationPreference.builder().type(NotificationType.HOUSEHOLD_MEMBER_LEFT)
                                .channel(NotificationChannel.EMAIL).build()));

        assertTrue(resolver.resolve(user, NotificationType.DIGEST).muted());
        assertEquals(Set.of(NotificationChannel.EMAIL),
                resolver.resolve(user, NotificationType.HOUSEHOLD_MEMBER_LEFT).outbound());
    }

    // ---- inbox-only types never go out ----

    @Test
    void anInboxOnlyTypeIsNeverMutedAndNeverGoesOut_withOrWithoutAPushToken() {
        var withToken = resolver.resolve(userWithPushToken().build(), NotificationType.PRICE_INFLATION);
        var withoutToken = resolver.resolve(userWithoutPushToken().build(), NotificationType.PRICE_INFLATION);

        assertFalse(withToken.muted());
        assertTrue(withToken.outbound().isEmpty());
        assertFalse(withoutToken.muted());
        assertTrue(withoutToken.outbound().isEmpty());
    }
}
