package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.NotificationNoticeOptionRepository;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeOptionStoreTest {

    @Mock private NotificationNoticeOptionRepository optionRepository;
    @Mock private TwilioMessageClient twilioMessageClient;

    private NoticeOptionStore store;

    @BeforeEach
    void setUp() {
        store = new NoticeOptionStore(optionRepository, new WhatsAppChannelPolicy(twilioMessageClient));
    }

    private static User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e").role(Role.USER).build();
    }

    private static NotificationNoticeOption option(NotificationNotice notice, NotificationChannel channel,
                                                   boolean enabled) {
        return NotificationNoticeOption.builder().notice(notice).channel(channel).enabled(enabled).build();
    }

    // ---- reading ----

    @Test
    void untouchedOptionsFollowTheDefaults() {
        var options = store.optionsOf(user());

        assertTrue(options.isEnabled(NotificationNotice.RECEIPT_READY, NotificationChannel.PUSH));
        assertTrue(options.isEnabled(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH));
        assertFalse(options.isEnabled(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL));
        assertFalse(options.isEnabled(NotificationNotice.DEALS_DIGEST, NotificationChannel.WHATSAPP));
        assertFalse(options.isEnabled(NotificationNotice.WEEKLY_REPORT, NotificationChannel.EMAIL));
        assertFalse(options.isEnabled(NotificationNotice.WEEKLY_REPORT, NotificationChannel.WHATSAPP));
        assertTrue(options.isEnabled(NotificationNotice.ACCOUNT, NotificationChannel.PUSH));
        assertFalse(options.isEnabled(NotificationNotice.ACCOUNT, NotificationChannel.EMAIL));
        assertFalse(options.isConfigured(NotificationNotice.DEALS_DIGEST));
    }

    @Test
    void storedOptionsOverrideTheDefaults() {
        var user = user();
        when(optionRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH, false),
                option(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true)));

        var options = store.optionsOf(user);

        assertFalse(options.isEnabled(NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH));
        assertTrue(options.isEnabled(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL));
        assertTrue(options.isConfigured(NotificationNotice.PRICE_ALERTS));
        assertTrue(options.isEnabled(NotificationNotice.STOCKOUT, NotificationChannel.PUSH));
    }

    @Test
    void aChannelTheNoticeDoesNotOfferIsNeverOn() {
        var user = user();
        when(optionRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                option(NotificationNotice.STOCKOUT, NotificationChannel.EMAIL, true)));

        assertFalse(store.optionsOf(user).isEnabled(NotificationNotice.STOCKOUT, NotificationChannel.EMAIL));
    }

    @Test
    void accountEmailFollowsWhatIsStored() {
        var user = user();
        when(optionRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                option(NotificationNotice.ACCOUNT, NotificationChannel.EMAIL, true)));

        assertTrue(store.optionsOf(user).isEnabled(NotificationNotice.ACCOUNT, NotificationChannel.EMAIL));
    }

    @Test
    void weeklyReportWhatsAppOptionIsReadFromTheUserRow() {
        var user = user();
        user.setWhatsAppWeeklyReportEnabled(true);

        assertTrue(store.optionsOf(user).isEnabled(NotificationNotice.WEEKLY_REPORT, NotificationChannel.WHATSAPP));
    }

    // ---- writing ----

    @Test
    void saveOptionCreatesTheRowWhenThereIsNone() {
        var user = user();

        store.saveOption(user, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);

        var saved = ArgumentCaptor.forClass(NotificationNoticeOption.class);
        verify(optionRepository).save(saved.capture());
        assertSame(user, saved.getValue().getUser());
        assertEquals(NotificationNotice.PRICE_ALERTS, saved.getValue().getNotice());
        assertEquals(NotificationChannel.EMAIL, saved.getValue().getChannel());
        assertTrue(saved.getValue().isEnabled());
    }

    @Test
    void saveOptionUpdatesTheExistingRow() {
        var user = user();
        var existing = option(NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);
        when(optionRepository.findAllByUserIdAndNotice(user.getId(), NotificationNotice.PRICE_ALERTS))
                .thenReturn(List.of(existing));

        store.saveOption(user, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, false);

        verify(optionRepository).save(existing);
        assertFalse(existing.isEnabled());
    }

    @Test
    void saveOptionIgnoresAChannelNotOffered() {
        var user = user();

        store.saveOption(user, NotificationNotice.STOCKOUT, NotificationChannel.WHATSAPP, true);

        verify(optionRepository, never()).save(any());
    }

    @Test
    void weeklyReportWhatsAppOptionIsWrittenToTheUserRow() {
        var user = user();

        store.saveOption(user, NotificationNotice.WEEKLY_REPORT, NotificationChannel.WHATSAPP, true);

        assertTrue(user.isWhatsAppWeeklyReportEnabled());
        verify(optionRepository, never()).save(any());
    }

    @Test
    void saveAllOptionsOffSwitchesEveryChannelOff() {
        var user = user();

        store.saveAllOptionsOff(user, NotificationNotice.ACCOUNT);

        var saved = ArgumentCaptor.forClass(NotificationNoticeOption.class);
        verify(optionRepository, times(2)).save(saved.capture());
        assertEquals(List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                saved.getAllValues().stream().map(NotificationNoticeOption::getChannel).toList());
        assertTrue(saved.getAllValues().stream().noneMatch(NotificationNoticeOption::isEnabled));
    }

    // ---- digest frequency kept in step with the DEALS_DIGEST options ----

    @Test
    void digestGoesOffAndRemembersTheChoiceWhenNoOptionIsOn() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.WEEKLY);
        when(optionRepository.findAllByUserIdAndNotice(user.getId(), NotificationNotice.DEALS_DIGEST))
                .thenReturn(List.of(option(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false)));

        store.alignDigestFrequencyWithOptions(user);

        assertEquals(DigestFrequency.OFF, user.getDigestFrequency());
        assertEquals(DigestFrequency.WEEKLY, user.getDigestFrequencyChoice());
        assertEquals(DigestFrequency.WEEKLY, NoticeOptionStore.chosenDigestFrequency(user));
    }

    @Test
    void digestComesBackAtTheRememberedChoiceWhenAnOptionIsOn() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.OFF);
        user.setDigestFrequencyChoice(DigestFrequency.WEEKLY);
        when(optionRepository.findAllByUserIdAndNotice(user.getId(), NotificationNotice.DEALS_DIGEST))
                .thenReturn(List.of(
                        option(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false),
                        option(NotificationNotice.DEALS_DIGEST, NotificationChannel.EMAIL, true)));

        store.alignDigestFrequencyWithOptions(user);

        assertEquals(DigestFrequency.WEEKLY, user.getDigestFrequency());
    }

    @Test
    void digestComesBackDailyWhenNoChoiceWasRemembered() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.OFF);

        store.alignDigestFrequencyWithOptions(user);

        assertEquals(DigestFrequency.DAILY, user.getDigestFrequency());
    }

    @Test
    void digestFrequencyIsLeftAloneWhileItAgreesWithTheOptions() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.WEEKLY);

        store.alignDigestFrequencyWithOptions(user);

        assertEquals(DigestFrequency.WEEKLY, user.getDigestFrequency());
    }

    @Test
    void aWhatsAppOptionTheUserCannotUseDoesNotKeepTheDigestOn() {
        var user = user();
        user.setDigestFrequency(DigestFrequency.DAILY);
        when(optionRepository.findAllByUserIdAndNotice(user.getId(), NotificationNotice.DEALS_DIGEST))
                .thenReturn(List.of(
                        option(NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, false),
                        option(NotificationNotice.DEALS_DIGEST, NotificationChannel.WHATSAPP, true)));

        assertFalse(store.hasAnyOptionOn(user, NotificationNotice.DEALS_DIGEST));
    }
}
