package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationSystemSetting;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.repository.NotificationSystemSettingRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Fonte única da configuração das notificações de SISTEMA (tabela
 * {@code notification_system_settings}, editável no dashboard). Os jobs
 * perguntam aqui se devem rodar e com quais textos — o que era hardcoded
 * (cron + i18n) vira dado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final NotificationSystemSettingRepository repository;
    private final LocalizedMessageService messageService;

    public NotificationSystemSetting get(NotificationJob job) {
        return repository.findByJob(job)
                .orElseThrow(() -> new IllegalStateException("settings row missing for " + job + " (seeded by V96)"));
    }

    public boolean isEnabled(NotificationJob job) {
        return get(job).isEnabled();
    }

    /**
     * Gate + claim do tick horário pros jobs com agenda (semanal/mensal): true
     * só quando habilitado, a agenda bate com AGORA (hora local >= sendHour,
     * dia da semana/mês quando configurados) e esta chamada VENCEU o claim
     * atômico do dia — restart ou segunda instância não duplicam o envio.
     */
    @Transactional
    public boolean claimDueDaily(NotificationJob job, ZonedDateTime now) {
        var setting = get(job);
        if (!setting.isEnabled()) return false;
        if (setting.getSendHour() != null && now.getHour() < setting.getSendHour()) return false;
        if (setting.getDayOfWeek() != null && now.getDayOfWeek().getValue() != setting.getDayOfWeek()) return false;
        if (setting.getDayOfMonth() != null && now.getDayOfMonth() != setting.getDayOfMonth()) return false;
        return repository.claimRun(job, now.toLocalDate()) > 0;
    }

    /** Título do job: override do dashboard quando houver, senão o default do i18n. */
    public String resolveTitle(NotificationJob job, Locale locale, String defaultKey, Object... args) {
        var setting = get(job);
        var override = isPortuguese(locale) ? setting.getTitlePt() : setting.getTitleEn();
        return format(override, defaultKey, locale, args);
    }

    /** Corpo do job: override do dashboard quando houver, senão o default do i18n. */
    public String resolveBody(NotificationJob job, Locale locale, String defaultKey, Object... args) {
        var setting = get(job);
        var override = isPortuguese(locale) ? setting.getBodyPt() : setting.getBodyEn();
        return format(override, defaultKey, locale, args);
    }

    private String format(String override, String defaultKey, Locale locale, Object... args) {
        if (override == null || override.isBlank()) {
            return messageService.translate(defaultKey, locale, args);
        }
        try {
            return new MessageFormat(override, locale).format(args);
        } catch (IllegalArgumentException ex) {
            // Override mal formado nunca derruba o envio — cai no default e avisa.
            log.warn("notification.settings.bad_template key={} reason={}", defaultKey, ex.getMessage());
            return messageService.translate(defaultKey, locale, args);
        }
    }

    private boolean isPortuguese(Locale locale) {
        return !"en".equalsIgnoreCase(locale.getLanguage());
    }
}
