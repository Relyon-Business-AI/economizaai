package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.service.admin.CampaignDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Drives the campaign state machine. "Send now" and scheduled sends are the
 * same thing — a SCHEDULED campaign whose {@code scheduledAt} has passed — so
 * this poll is the only dispatcher. Also the mandatory sweeper for the async
 * state machine: SENDING rows stranded by a restart mid-dispatch are
 * force-failed after a timeout instead of looking "in flight" forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignScheduler {

    private static final int STALE_SENDING_MINUTES = 30;

    private final NotificationCampaignRepository campaignRepository;
    private final CampaignDispatchService dispatchService;

    @Scheduled(fixedDelayString = "${economizaai.notifications.campaigns.poll-ms:15000}")
    public void run() {
        sweepStaleSending();
        var due = campaignRepository.findByStatusAndScheduledAtLessThanEqual(
                CampaignStatus.SCHEDULED, OffsetDateTime.now());
        for (var campaign : due) {
            try {
                dispatchService.dispatch(campaign.getId());
            } catch (RuntimeException ex) {
                // Isolate per-campaign failures so one bad campaign can't block the queue.
                log.error("campaign.dispatch_failed campaign={} reason={}", campaign.getId(), ex.getMessage());
            }
        }
    }

    // No @Transactional: self-invocation from run() would bypass the proxy anyway;
    // each save commits in the repository's own short transaction, which is enough.
    private void sweepStaleSending() {
        var cutoff = LocalDateTime.now().minusMinutes(STALE_SENDING_MINUTES);
        for (var stranded : campaignRepository.findByStatusAndUpdatedAtBefore(CampaignStatus.SENDING, cutoff)) {
            stranded.setStatus(CampaignStatus.FAILED);
            stranded.setCompletedAt(OffsetDateTime.now());
            campaignRepository.save(stranded);
            log.warn("campaign.sweeper.force_failed campaign={} name={} stuckSince={}",
                    stranded.getId(), stranded.getName(), stranded.getUpdatedAt());
        }
    }
}
