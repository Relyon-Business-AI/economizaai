package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.UpdateNotificationSettingRequest;
import com.relyon.economizaai.dto.response.NotificationSettingResponse;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.repository.NotificationSystemSettingRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CRUD (dashboard) das notificações de SISTEMA. As linhas são seedadas pela
 * migração — aqui só lê e edita; o efeito é imediato (os jobs leem a cada
 * execução, sem redeploy).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationSettingsService {

    private static final Locale PT = Locale.forLanguageTag("pt");

    private final NotificationSystemSettingRepository repository;
    private final LocalizedMessageService messageService;

    @Transactional(readOnly = true)
    public List<NotificationSettingResponse> list() {
        return Arrays.stream(NotificationJob.values()).map(this::toResponse).toList();
    }

    @Transactional
    public NotificationSettingResponse update(NotificationJob job, UpdateNotificationSettingRequest request,
                                              String adminEmail) {
        var setting = repository.findByJob(job).orElseThrow();
        setting.setEnabled(request.enabled());
        if (supportsSchedule(job)) {
            if (request.sendHour() != null) setting.setSendHour(request.sendHour());
            if (job == NotificationJob.WEEKLY_DIGEST && request.dayOfWeek() != null) {
                setting.setDayOfWeek(request.dayOfWeek());
            }
            if (job == NotificationJob.MONTHLY_DIGEST && request.dayOfMonth() != null) {
                setting.setDayOfMonth(request.dayOfMonth());
            }
        }
        if (supportsTemplates(job)) {
            setting.setTitlePt(blankToNull(request.titlePt()));
            setting.setTitleEn(blankToNull(request.titleEn()));
            setting.setBodyPt(blankToNull(request.bodyPt()));
            setting.setBodyEn(blankToNull(request.bodyEn()));
        }
        setting.setUpdatedBy(adminEmail);
        repository.save(setting);
        log.info("admin.notification_settings.updated job={} enabled={} by={}",
                job, request.enabled(), adminEmail);
        return toResponse(job);
    }

    private NotificationSettingResponse toResponse(NotificationJob job) {
        var setting = repository.findByJob(job).orElseThrow();
        return NotificationSettingResponse.from(setting,
                defaultTitlePt(job), defaultBodyPt(job),
                supportsSchedule(job), supportsTemplates(job));
    }

    /** Semanal/mensal têm agenda editável; os demais são por-usuário ou por intervalo. */
    private boolean supportsSchedule(NotificationJob job) {
        return job == NotificationJob.WEEKLY_DIGEST || job == NotificationJob.MONTHLY_DIGEST;
    }

    /** Só os resumos têm texto simples o bastante pra override (os outros compõem o corpo dinamicamente). */
    private boolean supportsTemplates(NotificationJob job) {
        return job == NotificationJob.WEEKLY_DIGEST || job == NotificationJob.MONTHLY_DIGEST;
    }

    private String defaultTitlePt(NotificationJob job) {
        return switch (job) {
            case WEEKLY_DIGEST -> messageService.translate("notification.digest.title", PT);
            case MONTHLY_DIGEST -> messageService.translate("notification.digest.monthly.title", PT, "{0}");
            case DEALS_DIGEST -> messageService.translate("notification.deals.title", PT);
            case BUDGET_ALERT, REPLENISHMENT -> null;
        };
    }

    private String defaultBodyPt(NotificationJob job) {
        return switch (job) {
            case WEEKLY_DIGEST -> messageService.translate("notification.digest.body", PT, "{0}", "{1}");
            case MONTHLY_DIGEST -> messageService.translate("notification.digest.monthly.body", PT, "{0}", "{1}", "{2}");
            case DEALS_DIGEST, BUDGET_ALERT, REPLENISHMENT -> null;
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
