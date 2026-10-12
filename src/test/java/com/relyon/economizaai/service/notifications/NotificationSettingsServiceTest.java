package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.repository.NotificationSystemSettingRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSettingsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Locale PT = Locale.forLanguageTag("pt");

    @Mock private NotificationSystemSettingRepository repository;

    private NotificationSettingsService service() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new NotificationSettingsService(repository, new LocalizedMessageService(source));
    }

    private void stubSetting(NotificationSystemSetting setting) {
        when(repository.findByJob(setting.getJob())).thenReturn(Optional.of(setting));
    }

    // Segunda-feira 09:00 local.
    private ZonedDateTime mondayAt(int hour) {
        return ZonedDateTime.of(2026, 10, 12, hour, 0, 0, 0, ZONE);
    }

    @Test
    void claimSkipsWhenDisabled() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(false).sendHour(8).dayOfWeek(1).build());
        assertFalse(service().claimDueDaily(NotificationJob.WEEKLY_DIGEST, mondayAt(9)));
        verify(repository, never()).claimRun(any(), any());
    }

    @Test
    void claimSkipsBeforeSendHourAndOnWrongDay() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).sendHour(8).dayOfWeek(1).build());
        assertFalse(service().claimDueDaily(NotificationJob.WEEKLY_DIGEST, mondayAt(7)), "antes da hora");

        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).sendHour(8).dayOfWeek(3).build());
        assertFalse(service().claimDueDaily(NotificationJob.WEEKLY_DIGEST, mondayAt(9)), "dia errado");
        verify(repository, never()).claimRun(any(), any());
    }

    @Test
    void claimFiresOnceWhenScheduleMatchesAndClaimWins() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).sendHour(8).dayOfWeek(1).build());
        var now = mondayAt(9);
        when(repository.claimRun(NotificationJob.WEEKLY_DIGEST, now.toLocalDate())).thenReturn(1);
        assertTrue(service().claimDueDaily(NotificationJob.WEEKLY_DIGEST, now));
    }

    @Test
    void claimLosesWhenAnotherInstanceAlreadyRanToday() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).sendHour(8).dayOfWeek(1).build());
        var now = mondayAt(10);
        when(repository.claimRun(NotificationJob.WEEKLY_DIGEST, now.toLocalDate())).thenReturn(0);
        assertFalse(service().claimDueDaily(NotificationJob.WEEKLY_DIGEST, now));
    }

    @Test
    void resolveUsesOverrideWhenPresentAndFallsBackOnBadTemplate() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true)
                .titlePt("Resumão da semana 💸").bodyPt("Foram {0} notas, R$ {1}!").build());
        var service = service();
        assertEquals("Resumão da semana 💸",
                service.resolveTitle(NotificationJob.WEEKLY_DIGEST, PT, "notification.digest.title"));
        assertEquals("Foram 3 notas, R$ 99!",
                service.resolveBody(NotificationJob.WEEKLY_DIGEST, PT, "notification.digest.body", "3", "99"));

        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).titlePt("quebrado {9x}").build());
        assertEquals("Seu resumo da semana",
                service.resolveTitle(NotificationJob.WEEKLY_DIGEST, PT, "notification.digest.title"),
                "template inválido cai no default sem derrubar o envio");
    }

    @Test
    void resolveFallsBackToI18nWhenNoOverride() {
        stubSetting(NotificationSystemSetting.builder()
                .job(NotificationJob.WEEKLY_DIGEST).enabled(true).build());
        assertEquals("Seu resumo da semana",
                service().resolveTitle(NotificationJob.WEEKLY_DIGEST, PT, "notification.digest.title"));
    }
}
