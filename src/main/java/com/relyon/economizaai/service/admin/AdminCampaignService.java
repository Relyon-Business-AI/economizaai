package com.relyon.economizaai.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.request.SaveCampaignRequest;
import com.relyon.economizaai.dto.response.CampaignMetricsResponse;
import com.relyon.economizaai.dto.response.CampaignMetricsResponse.EventLine;
import com.relyon.economizaai.dto.response.CampaignResponse;
import com.relyon.economizaai.dto.response.CampaignResponse.Metrics;
import com.relyon.economizaai.exception.InvalidCampaignRecipientsException;
import com.relyon.economizaai.exception.InvalidCampaignScheduleException;
import com.relyon.economizaai.exception.InvalidCampaignStateException;
import com.relyon.economizaai.exception.NotificationAudienceNotFoundException;
import com.relyon.economizaai.exception.NotificationCampaignNotFoundException;
import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationAudienceRepository;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.CampaignEventTally;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRepository.CampaignTally;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRUD + state machine + metrics for admin notification campaigns.
 *
 * <p>Sending is scheduler-driven: both "send now" and a future schedule put the
 * campaign in SCHEDULED (send-now with {@code scheduledAt = now}) and the
 * {@code CampaignScheduler} poll picks it up within seconds — the HTTP request
 * never blocks on the outbound dispatch loop.
 *
 * <p>Metrics reuse the existing tables end-to-end: funnel (sent/delivered/read)
 * from the notifications outbox, engagement (PUSH_OPENED / DEAL_TAPPED / ...)
 * from notification_events joined through {@code notifications.campaign_id},
 * and conversions as CONVERTED events by the campaign's recipients inside the
 * attribution window after the send.
 */
@Slf4j
@Service
public class AdminCampaignService {

    private final NotificationCampaignRepository campaignRepository;
    private final NotificationAudienceRepository audienceRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationEventRepository eventRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final int conversionWindowDays;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminCampaignService(NotificationCampaignRepository campaignRepository,
                                NotificationAudienceRepository audienceRepository,
                                NotificationRepository notificationRepository,
                                NotificationEventRepository eventRepository,
                                NotificationService notificationService,
                                UserRepository userRepository,
                                @Value("${economizaai.attribution.window-days:14}") int conversionWindowDays) {
        this.campaignRepository = campaignRepository;
        this.audienceRepository = audienceRepository;
        this.notificationRepository = notificationRepository;
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.conversionWindowDays = conversionWindowDays;
    }

    @Transactional(readOnly = true)
    public Page<CampaignResponse> list(Pageable pageable) {
        var page = campaignRepository.findAllWithAudience(pageable);
        var metricsByCampaign = metricsFor(page.getContent());
        return page.map(campaign -> CampaignResponse.from(campaign, metricsByCampaign.get(campaign.getId())));
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(UUID campaignId) {
        var campaign = requireCampaign(campaignId);
        return CampaignResponse.from(campaign, metricsFor(List.of(campaign)).get(campaignId));
    }

    @Transactional
    public CampaignResponse create(SaveCampaignRequest request, String adminEmail) {
        var campaign = applyContent(NotificationCampaign.builder().build(), request);
        campaign.setCreatedByEmail(adminEmail);
        var saved = campaignRepository.save(campaign);
        log.info("admin.campaign.created id={} name={} status={} audience={} recipients={}",
                saved.getId(), saved.getName(), saved.getStatus(),
                saved.getAudience() != null ? saved.getAudience().getName() : null,
                saved.getRecipientUserIds().size());
        return CampaignResponse.from(saved, emptyMetrics());
    }

    @Transactional
    public CampaignResponse update(UUID campaignId, SaveCampaignRequest request) {
        var campaign = requireCampaign(campaignId);
        if (!campaign.getStatus().isEditable()) {
            throw new InvalidCampaignStateException(campaign.getStatus().name(), "update");
        }
        var saved = campaignRepository.save(applyContent(campaign, request));
        log.info("admin.campaign.updated id={} name={} status={}", saved.getId(), saved.getName(), saved.getStatus());
        return CampaignResponse.from(saved, metricsFor(List.of(saved)).get(campaignId));
    }

    /** "Send now": enter SCHEDULED with scheduledAt = now; the scheduler poll dispatches it. */
    @Transactional
    public CampaignResponse send(UUID campaignId) {
        var campaign = requireCampaign(campaignId);
        if (!campaign.getStatus().isSendable()) {
            throw new InvalidCampaignStateException(campaign.getStatus().name(), "send");
        }
        campaign.setStatus(CampaignStatus.SCHEDULED);
        campaign.setScheduledAt(OffsetDateTime.now());
        var saved = campaignRepository.save(campaign);
        log.info("admin.campaign.send_requested id={} name={}", campaignId, saved.getName());
        return CampaignResponse.from(saved, metricsFor(List.of(saved)).get(campaignId));
    }

    @Transactional
    public CampaignResponse cancel(UUID campaignId) {
        var campaign = requireCampaign(campaignId);
        if (!campaign.getStatus().isEditable()) {
            throw new InvalidCampaignStateException(campaign.getStatus().name(), "cancel");
        }
        campaign.setStatus(CampaignStatus.CANCELLED);
        var saved = campaignRepository.save(campaign);
        log.info("admin.campaign.cancelled id={} name={}", campaignId, saved.getName());
        return CampaignResponse.from(saved, metricsFor(List.of(saved)).get(campaignId));
    }

    @Transactional
    public void delete(UUID campaignId) {
        var campaign = requireCampaign(campaignId);
        var deletable = campaign.getStatus() == CampaignStatus.DRAFT
                || campaign.getStatus() == CampaignStatus.CANCELLED
                || campaign.getStatus() == CampaignStatus.FAILED;
        if (!deletable) {
            throw new InvalidCampaignStateException(campaign.getStatus().name(), "delete");
        }
        campaignRepository.delete(campaign);
        log.info("admin.campaign.deleted id={} name={}", campaignId, campaign.getName());
    }

    /**
     * Smoke test: dispatch the campaign's content to the CALLING admin only,
     * without tagging the outbox row with the campaign id — test sends never
     * pollute the campaign's metrics. Allowed in any status.
     */
    public void sendTest(UUID campaignId, User admin) {
        var campaign = requireCampaign(campaignId);
        var extras = new HashMap<String, Object>();
        extras.put("source", "campaign_test");
        extras.put("campaignId", campaignId.toString());
        notificationService.notify(new NotificationPayload(
                admin, campaign.getType(), campaign.getTitle(), campaign.getBody(), extras));
        log.info("admin.campaign.test_sent id={} target={}", campaignId, LogMasker.email(admin.getEmail()));
    }

    @Transactional(readOnly = true)
    public CampaignMetricsResponse metrics(UUID campaignId) {
        var campaign = requireCampaign(campaignId);
        var eventTallies = eventRepository.tallyEventsByCampaigns(List.of(campaignId));
        var eventLines = eventTallies.stream()
                .sorted(Comparator.comparing(CampaignEventTally::getEventType))
                .map(tally -> new EventLine(tally.getEventType(), tally.getOccurrences(), tally.getUsers()))
                .toList();
        return new CampaignMetricsResponse(
                campaignId, campaign.getName(),
                metricsFor(List.of(campaign)).get(campaignId),
                eventLines, conversionWindowDays);
    }

    private NotificationCampaign requireCampaign(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new NotificationCampaignNotFoundException(String.valueOf(campaignId)));
    }

    private NotificationCampaign applyContent(NotificationCampaign campaign, SaveCampaignRequest request) {
        applyTarget(campaign, request);
        campaign.setName(request.name());
        campaign.setTitle(request.title());
        campaign.setBody(request.body());
        campaign.setType(request.type() != null ? request.type() : NotificationType.SYSTEM);
        campaign.setExtras(serialize(request.extras()));
        if (request.scheduledAt() != null) {
            if (request.scheduledAt().isBefore(OffsetDateTime.now())) {
                throw new InvalidCampaignScheduleException();
            }
            campaign.setStatus(CampaignStatus.SCHEDULED);
            campaign.setScheduledAt(request.scheduledAt());
        } else {
            campaign.setStatus(CampaignStatus.DRAFT);
            campaign.setScheduledAt(null);
        }
        return campaign;
    }

    /**
     * A campaign targets EXACTLY ONE source: a filter-based audience or a
     * hand-picked user list. Unknown recipient ids are dropped; an empty
     * resolved list (or both/neither source) is a 400.
     */
    private void applyTarget(NotificationCampaign campaign, SaveCampaignRequest request) {
        var hasAudience = request.audienceId() != null;
        var hasRecipients = request.recipientUserIds() != null && !request.recipientUserIds().isEmpty();
        if (hasAudience == hasRecipients) throw new InvalidCampaignRecipientsException();
        if (hasAudience) {
            var audience = audienceRepository.findById(request.audienceId())
                    .orElseThrow(() -> new NotificationAudienceNotFoundException(String.valueOf(request.audienceId())));
            campaign.setAudience(audience);
            campaign.getRecipientUserIds().clear();
            return;
        }
        var knownRecipientIds = userRepository.findAllById(request.recipientUserIds()).stream()
                .filter(User::isActive)
                .map(User::getId)
                .collect(Collectors.toSet());
        if (knownRecipientIds.isEmpty()) throw new InvalidCampaignRecipientsException();
        campaign.setAudience(null);
        campaign.getRecipientUserIds().clear();
        campaign.getRecipientUserIds().addAll(knownRecipientIds);
    }

    private String serialize(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(extras);
        } catch (Exception ex) {
            log.warn("admin.campaign.extras_serialize_failed reason={}", ex.getMessage());
            return null;
        }
    }

    /** One funnel query + one engagement query for the whole batch; conversions per sent campaign. */
    private Map<UUID, Metrics> metricsFor(List<NotificationCampaign> campaigns) {
        if (campaigns.isEmpty()) return Map.of();
        var campaignIds = campaigns.stream().map(NotificationCampaign::getId).toList();
        var funnelByCampaign = notificationRepository.tallyByCampaigns(campaignIds).stream()
                .collect(Collectors.toMap(CampaignTally::getCampaignId, Function.identity()));
        var eventsByCampaign = eventRepository.tallyEventsByCampaigns(campaignIds).stream()
                .collect(Collectors.groupingBy(CampaignEventTally::getCampaignId));

        return campaigns.stream().collect(Collectors.toMap(NotificationCampaign::getId, campaign ->
                buildMetrics(campaign,
                        funnelByCampaign.get(campaign.getId()),
                        eventsByCampaign.getOrDefault(campaign.getId(), List.of()))));
    }

    private Metrics buildMetrics(NotificationCampaign campaign, CampaignTally funnel,
                                 List<CampaignEventTally> events) {
        if (funnel == null) return emptyMetrics();
        var pushOpened = distinctUsers(events, NotificationEventType.PUSH_OPENED);
        var tapped = distinctUsers(events, NotificationEventType.DEAL_TAPPED);
        var dismissed = distinctUsers(events, NotificationEventType.DISMISSED);

        var convertedUsers = 0L;
        var conversions = 0L;
        var attributedSavings = BigDecimal.ZERO;
        if (campaign.getStartedAt() != null) {
            var rollup = eventRepository.conversionsForCampaign(campaign.getId(),
                    campaign.getStartedAt(), campaign.getStartedAt().plusDays(conversionWindowDays));
            convertedUsers = rollup.getConvertedUsers();
            conversions = rollup.getConversions();
            attributedSavings = rollup.getSavings();
        }
        return new Metrics(
                funnel.getSent(), funnel.getDelivered(), funnel.getRead(),
                pushOpened, tapped, dismissed, convertedUsers, conversions, attributedSavings,
                rate(funnel.getDelivered(), funnel.getSent()),
                rate(funnel.getRead(), funnel.getSent()),
                rate(pushOpened, funnel.getSent()),
                rate(convertedUsers, funnel.getSent()));
    }

    private long distinctUsers(List<CampaignEventTally> events, NotificationEventType type) {
        return events.stream()
                .filter(tally -> tally.getEventType() == type)
                .mapToLong(CampaignEventTally::getUsers)
                .sum();
    }

    private static Metrics emptyMetrics() {
        return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
