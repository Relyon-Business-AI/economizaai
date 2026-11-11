package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.service.LocalizedMessageService;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Planta no sininho (inbox in-app) um convite pra ativar as notificações push,
 * só pra quem NUNCA registrou um token — é por push que avisamos "nota pronta"
 * e quedas de preço, e quem instalou antes da 1.3.1 nunca viu o pedido.
 *
 * <p>Inbox-only de propósito (grava direto via repository, sem dispatch): o
 * destinatário por definição não tem push, e e-mail pra isso seria spam.
 * Re-convida no máximo 1x a cada {@value #RENUDGE_DAYS} dias; quem ativa some
 * do público na rodada seguinte (filtro é token nulo).
 *
 * <p>Roda em páginas: os candidatos já saem do banco sem quem foi convidado há
 * pouco, e cada página é gravada numa transação curta própria.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushOptInNudgeScheduler {

    /** Marcador no payload — também é o `destination` que o app usa pro CTA "Ativar". */
    static final String DESTINATION = "ENABLE_PUSH";
    static final int RENUDGE_DAYS = 30;
    private static final int PAGE_SIZE = 500;
    /** Sorts before every real id — the starting point of the keyset pagination. */
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final LocalizedMessageService messageService;

    @Scheduled(cron = "${economizaai.notifications.push-optin-nudge-cron:0 0 12 * * TUE}",
            zone = "America/Sao_Paulo")
    public void nudgeUsersWithoutPushToken() {
        var nudgedAfter = BrazilClock.nowDateTime().minusDays(RENUDGE_DAYS);
        var created = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var candidates = userRepository.findPushOptInNudgeCandidates(
                    nudgedAfter, afterId, PageRequest.of(0, PAGE_SIZE));
            if (candidates.isEmpty()) break;
            created += saveNudgesIsolated(candidates);
            afterId = candidates.get(candidates.size() - 1).getId();
        }
        if (created > 0) {
            log.info("push_optin_nudge.done created={}", created);
        }
    }

    /** One page = one short transaction ({@code saveAll}); a failed page is logged and the run goes on. */
    private int saveNudgesIsolated(List<User> candidates) {
        try {
            notificationRepository.saveAll(candidates.stream().map(this::buildNudge).toList());
            return candidates.size();
        } catch (RuntimeException ex) {
            log.warn("push_optin_nudge.page_failed users={} reason={}", candidates.size(), ex.getMessage());
            return 0;
        }
    }

    private Notification buildNudge(User user) {
        var locale = LocalizedMessageService.toLocale(user.getLocale());
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
