package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.service.LocalizedMessageService;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planta no sininho (inbox in-app) um convite pra ativar as notificações push,
 * só pra quem NUNCA registrou um token — é por push que avisamos "nota pronta"
 * e quedas de preço, e quem instalou antes da 1.3.1 nunca viu o pedido.
 *
 * <p>Inbox-only de propósito (grava direto via repository, sem dispatch): o
 * destinatário por definição não tem push, e e-mail pra isso seria spam.
 * Re-convida no máximo 1x a cada {@value #RENUDGE_DAYS} dias; quem ativa some
 * do público na rodada seguinte (filtro é token nulo).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushOptInNudgeScheduler {

    /** Marcador no payload — também é o `destination` que o app usa pro CTA "Ativar". */
    static final String DESTINATION = "ENABLE_PUSH";
    static final int RENUDGE_DAYS = 30;

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final LocalizedMessageService messageService;

    @Scheduled(cron = "${economizaai.notifications.push-optin-nudge-cron:0 0 12 * * TUE}",
            zone = "America/Sao_Paulo")
    @Transactional
    public void nudgeUsersWithoutPushToken() {
        var candidates = userRepository.findByPushDeviceTokenIsNull();
        var created = 0;
        for (var user : candidates) {
            if (recentlyNudged(user)) continue;
            notificationRepository.save(buildNudge(user));
            created++;
        }
        if (created > 0) {
            log.info("push_optin_nudge.done candidates={} created={}", candidates.size(), created);
        }
    }

    private boolean recentlyNudged(User user) {
        return notificationRepository.existsByUserIdAndPayloadContainingAndCreatedAtAfter(
                user.getId(), DESTINATION, BrazilClock.nowDateTime().minusDays(RENUDGE_DAYS));
    }

    private Notification buildNudge(User user) {
        var locale = Locale.forLanguageTag(user.getLocale() == null ? "pt" : user.getLocale());
        return Notification.builder()
                .user(user)
                .type(NotificationType.PUSH_OPTIN)
                .channel(NotificationChannel.INBOX)
                .title(messageService.translate("notification.push_optin.title", locale))
                .body(messageService.translate("notification.push_optin.body", locale))
                .payload("{\"destination\":\"" + DESTINATION + "\"}")
                .delivered(false)
                .build();
    }
}
