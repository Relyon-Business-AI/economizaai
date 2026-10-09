package com.relyon.economizaai.service.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes one campaign send end-to-end. Same transaction split as
 * {@code ReceiptIngestionService}: claim the campaign and resolve the audience
 * in a short transaction, run the outbound dispatch loop UNTRANSACTED (each
 * push/email is a multi-second HTTP call — never pin a Hikari connection
 * across it), then persist the outcome in a second short transaction.
 *
 * <p>The claim is an atomic SCHEDULED → SENDING UPDATE, so two instances (or
 * overlapping scheduler ticks) can't double-send the same campaign.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignDispatchService {

    private final NotificationCampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final AdminAudienceService audienceService;
    private final NotificationService notificationService;
    private final NotificationEventService eventService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Snapshot taken inside the claim transaction so the loop runs detached. */
    private record ClaimedCampaign(NotificationCampaign campaign, List<User> recipients) {}

    public void dispatch(UUID campaignId) {
        var claimed = claim(campaignId);
        if (claimed == null) return;

        var campaign = claimed.campaign();
        var sent = 0;
        var failed = 0;
        for (var recipient : claimed.recipients()) {
            try {
                if (sendToRecipient(campaign, recipient)) sent++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("campaign.recipient_failed campaign={} user={} reason={}",
                        campaignId, LogMasker.email(recipient.getEmail()), ex.getMessage());
            }
        }
        finalizeSend(campaignId, claimed.recipients().size(), sent, failed);
        log.info("campaign.sent campaign={} name={} total={} sent={} failed={}",
                campaignId, campaign.getName(), claimed.recipients().size(), sent, failed);
    }

    /**
     * Short transaction: win the SCHEDULED → SENDING transition (or bail if
     * another instance did), resolve the audience to a concrete recipient list,
     * and record the recipient total. Null when the claim was lost.
     */
    private ClaimedCampaign claim(UUID campaignId) {
        return transactionTemplate.execute(status -> {
            var now = OffsetDateTime.now();
            var claimedRows = campaignRepository.claimForSending(
                    campaignId, CampaignStatus.SENDING, CampaignStatus.SCHEDULED, now, LocalDateTime.now());
            if (claimedRows == 0) {
                log.debug("campaign.claim_lost campaign={}", campaignId);
                return null;
            }
            var campaign = campaignRepository.findById(campaignId).orElseThrow();
            var recipients = resolveRecipients(campaign);
            campaign.setRecipientsTotal(recipients.size());
            campaignRepository.save(campaign);
            return new ClaimedCampaign(campaign, recipients);
        });
    }

    /** Audience campaigns resolve the live segment; explicit campaigns load the hand-picked users. */
    private List<User> resolveRecipients(NotificationCampaign campaign) {
        if (campaign.getAudience() != null) {
            return audienceService.resolveUsers(campaign.getAudience());
        }
        return userRepository.findAllById(campaign.getRecipientUserIds()).stream()
                .filter(User::isActive)
                .toList();
    }

    /** True when an outbox row was created (false = user opted out of the type). */
    private boolean sendToRecipient(NotificationCampaign campaign, User recipient) {
        var notification = notificationService.notify(new NotificationPayload(
                recipient, campaign.getType(), campaign.getTitle(), campaign.getBody(),
                buildExtras(campaign), campaign.getId()));
        if (notification == null) return false;
        eventService.record(recipient, NotificationEventType.SENT, RecordContext.builder()
                .notificationId(notification.getId())
                .channel(notification.getChannel().name())
                .metadata(Map.of("campaignId", campaign.getId().toString()))
                .build());
        return true;
    }

    /** Campaign extras + the campaign id, so the FE can attribute PUSH_OPENED to the campaign. */
    private Map<String, Object> buildExtras(NotificationCampaign campaign) {
        var extras = new HashMap<String, Object>(deserializeExtras(campaign));
        extras.put("campaignId", campaign.getId().toString());
        return extras;
    }

    private Map<String, Object> deserializeExtras(NotificationCampaign campaign) {
        if (campaign.getExtras() == null || campaign.getExtras().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(campaign.getExtras(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("campaign.extras_deserialize_failed campaign={} reason={}",
                    campaign.getId(), ex.getMessage());
            return Map.of();
        }
    }

    /** Second short transaction: record the outcome and close the campaign. */
    private void finalizeSend(UUID campaignId, int total, int sent, int failed) {
        transactionTemplate.executeWithoutResult(status ->
                campaignRepository.findById(campaignId).ifPresent(campaign -> {
                    campaign.setStatus(CampaignStatus.SENT);
                    campaign.setCompletedAt(OffsetDateTime.now());
                    campaign.setRecipientsTotal(total);
                    campaign.setRecipientsSent(sent);
                    campaign.setRecipientsFailed(failed);
                    campaignRepository.save(campaign);
                }));
    }
}
