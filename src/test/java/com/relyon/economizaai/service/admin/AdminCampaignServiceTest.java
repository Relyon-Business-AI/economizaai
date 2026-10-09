package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SaveCampaignRequest;
import com.relyon.economizaai.exception.InvalidCampaignRecipientsException;
import com.relyon.economizaai.exception.InvalidCampaignScheduleException;
import com.relyon.economizaai.exception.InvalidCampaignStateException;
import com.relyon.economizaai.exception.NotificationAudienceNotFoundException;
import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationAudienceRepository;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationEventRepository.CampaignConversionRollup;
import com.relyon.economizaai.repository.NotificationEventRepository.CampaignEventTally;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRepository.CampaignTally;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCampaignServiceTest {

    private static final int CONVERSION_WINDOW_DAYS = 14;

    @Mock private NotificationCampaignRepository campaignRepository;
    @Mock private NotificationAudienceRepository audienceRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationEventRepository eventRepository;
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;

    private AdminCampaignService service;

    private NotificationAudience audience;

    @BeforeEach
    void setUp() {
        service = new AdminCampaignService(campaignRepository, audienceRepository,
                notificationRepository, eventRepository, notificationService, userRepository,
                CONVERSION_WINDOW_DAYS);
        audience = NotificationAudience.builder().id(UUID.randomUUID()).name("Admins").builtIn(true).build();
    }

    private SaveCampaignRequest request(OffsetDateTime scheduledAt) {
        return new SaveCampaignRequest("Boas-vindas", "Bem-vindo!", "Escaneie sua primeira nota.",
                null, audience.getId(), null, scheduledAt, null);
    }

    private SaveCampaignRequest recipientsRequest(List<UUID> recipientUserIds) {
        return new SaveCampaignRequest("Direta", "Oi!", "Mensagem direta.",
                null, null, recipientUserIds, null, null);
    }

    private NotificationCampaign campaign(CampaignStatus status) {
        return NotificationCampaign.builder()
                .id(UUID.randomUUID()).name("Boas-vindas").title("Bem-vindo!").body("corpo")
                .type(NotificationType.SYSTEM).audience(audience).status(status)
                .build();
    }

    @Test
    void createWithoutScheduleStaysDraftAndDefaultsToSystemType() {
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        when(campaignRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(request(null), "admin@economizaai.app");

        assertEquals(CampaignStatus.DRAFT, response.status());
        assertEquals(NotificationType.SYSTEM, response.type());
        assertEquals("admin@economizaai.app", response.createdByEmail());
        assertNull(response.scheduledAt());
    }

    @Test
    void createWithFutureScheduleBecomesScheduled() {
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        when(campaignRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var scheduledAt = OffsetDateTime.now().plusHours(2);
        var response = service.create(request(scheduledAt), "admin@economizaai.app");

        assertEquals(CampaignStatus.SCHEDULED, response.status());
        assertEquals(scheduledAt, response.scheduledAt());
    }

    @Test
    void createRejectsPastSchedule() {
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        var pastRequest = request(OffsetDateTime.now().minusMinutes(5));
        assertThrows(InvalidCampaignScheduleException.class,
                () -> service.create(pastRequest, "admin@economizaai.app"));
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownAudience() {
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.empty());
        var unknownAudienceRequest = request(null);
        assertThrows(NotificationAudienceNotFoundException.class,
                () -> service.create(unknownAudienceRequest, "admin@economizaai.app"));
    }

    @Test
    void createWithExplicitRecipientsKeepsOnlyKnownActiveUsers() {
        var activeUser = User.builder().id(UUID.randomUUID()).email("admin1@economizaai.app").active(true).build();
        var inactiveUser = User.builder().id(UUID.randomUUID()).email("off@economizaai.app").active(false).build();
        var unknownId = UUID.randomUUID();
        when(userRepository.findAllById(any())).thenReturn(List.of(activeUser, inactiveUser));
        when(campaignRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(
                recipientsRequest(List.of(activeUser.getId(), inactiveUser.getId(), unknownId)),
                "admin@economizaai.app");

        assertEquals(List.of(activeUser.getId()), response.recipientUserIds());
        assertEquals(1, response.recipientCount());
        assertNull(response.audienceId());
    }

    @Test
    void createRejectsNeitherAudienceNorRecipients() {
        var emptyTargetRequest = recipientsRequest(List.of());
        assertThrows(InvalidCampaignRecipientsException.class,
                () -> service.create(emptyTargetRequest, "admin@economizaai.app"));
    }

    @Test
    void createRejectsBothAudienceAndRecipients() {
        var bothTargetsRequest = new SaveCampaignRequest("x", "t", "b", null,
                audience.getId(), List.of(UUID.randomUUID()), null, null);
        assertThrows(InvalidCampaignRecipientsException.class,
                () -> service.create(bothTargetsRequest, "admin@economizaai.app"));
    }

    @Test
    void createRejectsWhenNoRecipientResolves() {
        when(userRepository.findAllById(any())).thenReturn(List.of());
        var unknownOnlyRequest = recipientsRequest(List.of(UUID.randomUUID()));
        assertThrows(InvalidCampaignRecipientsException.class,
                () -> service.create(unknownOnlyRequest, "admin@economizaai.app"));
    }

    @Test
    void updateRejectsNonEditableStatus() {
        var sent = campaign(CampaignStatus.SENT);
        when(campaignRepository.findById(sent.getId())).thenReturn(Optional.of(sent));
        var updateRequest = request(null);
        assertThrows(InvalidCampaignStateException.class,
                () -> service.update(sent.getId(), updateRequest));
    }

    @Test
    void sendMovesDraftToScheduledNow() {
        var draft = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(campaignRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubEmptyMetrics(draft.getId());

        var response = service.send(draft.getId());

        assertEquals(CampaignStatus.SCHEDULED, response.status());
        assertNotNull(response.scheduledAt());
    }

    @Test
    void sendRejectsAlreadySentCampaign() {
        var sent = campaign(CampaignStatus.SENT);
        when(campaignRepository.findById(sent.getId())).thenReturn(Optional.of(sent));
        assertThrows(InvalidCampaignStateException.class, () -> service.send(sent.getId()));
    }

    @Test
    void cancelRejectsInFlightSend() {
        var sending = campaign(CampaignStatus.SENDING);
        when(campaignRepository.findById(sending.getId())).thenReturn(Optional.of(sending));
        assertThrows(InvalidCampaignStateException.class, () -> service.cancel(sending.getId()));
    }

    @Test
    void deleteRejectsSentCampaign() {
        var sent = campaign(CampaignStatus.SENT);
        when(campaignRepository.findById(sent.getId())).thenReturn(Optional.of(sent));
        assertThrows(InvalidCampaignStateException.class, () -> service.delete(sent.getId()));
        verify(campaignRepository, never()).delete(any(NotificationCampaign.class));
    }

    @Test
    void deleteRemovesDraft() {
        var draft = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.delete(draft.getId());
        verify(campaignRepository).delete(draft);
    }

    @Test
    void testSendTargetsCallingAdminWithoutCampaignTag() {
        var sent = campaign(CampaignStatus.SENT);
        when(campaignRepository.findById(sent.getId())).thenReturn(Optional.of(sent));
        var admin = User.builder().id(UUID.randomUUID()).email("admin@economizaai.app").build();

        service.sendTest(sent.getId(), admin);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(admin, payload.user());
        assertNull(payload.campaignId());
        assertEquals("campaign_test", payload.extras().get("source"));
    }

    @Test
    void metricsComputesRatesFromFunnelEngagementAndConversions() {
        var sentCampaign = campaign(CampaignStatus.SENT);
        sentCampaign.setStartedAt(OffsetDateTime.now().minusDays(2));
        when(campaignRepository.findById(sentCampaign.getId())).thenReturn(Optional.of(sentCampaign));
        when(notificationRepository.tallyByCampaigns(List.of(sentCampaign.getId())))
                .thenReturn(List.of(tally(sentCampaign.getId(), 10, 8, 4)));
        when(eventRepository.tallyEventsByCampaigns(List.of(sentCampaign.getId())))
                .thenReturn(List.of(eventTally(sentCampaign.getId(), NotificationEventType.PUSH_OPENED, 3, 2)));
        when(eventRepository.conversionsForCampaign(any(), any(), any()))
                .thenReturn(conversionRollup(1, 2, new BigDecimal("12.34")));

        var metrics = service.metrics(sentCampaign.getId());

        assertEquals(10, metrics.metrics().sent());
        assertEquals(8, metrics.metrics().delivered());
        assertEquals(4, metrics.metrics().read());
        assertEquals(2, metrics.metrics().pushOpened());
        assertEquals(1, metrics.metrics().convertedUsers());
        assertEquals(2, metrics.metrics().conversions());
        assertEquals(new BigDecimal("12.34"), metrics.metrics().attributedSavings());
        assertEquals(new BigDecimal("0.8000"), metrics.metrics().deliveryRate());
        assertEquals(new BigDecimal("0.4000"), metrics.metrics().readRate());
        assertEquals(new BigDecimal("0.2000"), metrics.metrics().openRate());
        assertEquals(new BigDecimal("0.1000"), metrics.metrics().conversionRate());
        assertEquals(CONVERSION_WINDOW_DAYS, metrics.conversionWindowDays());
        assertEquals(1, metrics.eventsByType().size());
    }

    @Test
    void metricsAreZeroForCampaignWithoutNotifications() {
        var draft = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        stubEmptyMetrics(draft.getId());

        var response = service.get(draft.getId());

        assertEquals(0, response.metrics().sent());
        assertEquals(BigDecimal.ZERO, response.metrics().conversionRate());
    }

    private void stubEmptyMetrics(UUID campaignId) {
        when(notificationRepository.tallyByCampaigns(List.of(campaignId))).thenReturn(List.of());
        when(eventRepository.tallyEventsByCampaigns(List.of(campaignId))).thenReturn(List.of());
    }

    private static CampaignTally tally(UUID campaignId, long sent, long delivered, long read) {
        return new CampaignTally() {
            @Override public UUID getCampaignId() { return campaignId; }
            @Override public long getSent() { return sent; }
            @Override public long getDelivered() { return delivered; }
            @Override public long getRead() { return read; }
        };
    }

    private static CampaignEventTally eventTally(UUID campaignId, NotificationEventType type,
                                                 long occurrences, long users) {
        return new CampaignEventTally() {
            @Override public UUID getCampaignId() { return campaignId; }
            @Override public NotificationEventType getEventType() { return type; }
            @Override public long getOccurrences() { return occurrences; }
            @Override public long getUsers() { return users; }
        };
    }

    private static CampaignConversionRollup conversionRollup(long users, long conversions, BigDecimal savings) {
        return new CampaignConversionRollup() {
            @Override public long getConvertedUsers() { return users; }
            @Override public long getConversions() { return conversions; }
            @Override public BigDecimal getSavings() { return savings; }
        };
    }
}
