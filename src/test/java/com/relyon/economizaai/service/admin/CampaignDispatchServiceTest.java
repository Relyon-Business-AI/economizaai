package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignDispatchServiceTest {

    @Mock private NotificationCampaignRepository campaignRepository;
    @Mock private AdminAudienceService audienceService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationEventService eventService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private CampaignDispatchService service;

    private NotificationCampaign campaign;
    private User firstRecipient;
    private User secondRecipient;

    @BeforeEach
    void setUp() {
        var audience = NotificationAudience.builder().id(UUID.randomUUID()).name("Admins").builtIn(true).build();
        campaign = NotificationCampaign.builder()
                .id(UUID.randomUUID()).name("Teste").title("Olá").body("corpo")
                .type(NotificationType.SYSTEM).audience(audience).status(CampaignStatus.SCHEDULED)
                .build();
        firstRecipient = User.builder().id(UUID.randomUUID()).email("admin1@economizaai.app").build();
        secondRecipient = User.builder().id(UUID.randomUUID()).email("admin2@economizaai.app").build();

        // Run both TransactionTemplate halves inline so the test exercises the real flow.
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        lenient().doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private void stubClaimWon(List<User> recipients) {
        when(campaignRepository.claimForSending(any(), any(), any(), any(), any())).thenReturn(1);
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(audienceService.resolveUsers(campaign.getAudience())).thenReturn(recipients);
    }

    @Test
    void lostClaimSendsNothing() {
        when(campaignRepository.claimForSending(any(), any(), any(), any(), any())).thenReturn(0);
        service.dispatch(campaign.getId());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void sendsToEveryRecipientAndRecordsSentTelemetry() {
        stubClaimWon(List.of(firstRecipient, secondRecipient));
        when(notificationService.notify(any())).thenAnswer(invocation -> {
            var payload = invocation.<NotificationPayload>getArgument(0);
            return Notification.builder()
                    .id(UUID.randomUUID()).user(payload.user()).type(payload.type())
                    .channel(NotificationChannel.PUSH).build();
        });

        service.dispatch(campaign.getId());

        var payloadCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService, times(2)).notify(payloadCaptor.capture());
        payloadCaptor.getAllValues().forEach(payload -> {
            assertEquals(campaign.getId(), payload.campaignId());
            assertEquals(campaign.getId().toString(), payload.extras().get("campaignId"));
        });
        verify(eventService, times(2)).record(any(), eq(NotificationEventType.SENT), any());
        assertEquals(CampaignStatus.SENT, campaign.getStatus());
        assertEquals(2, campaign.getRecipientsSent());
        assertEquals(0, campaign.getRecipientsFailed());
        assertEquals(2, campaign.getRecipientsTotal());
    }

    @Test
    void optedOutRecipientCountsAsNeitherSentNorFailed() {
        stubClaimWon(List.of(firstRecipient));
        when(notificationService.notify(any())).thenReturn(null);

        service.dispatch(campaign.getId());

        verify(eventService, never()).record(any(), any(), any());
        assertEquals(CampaignStatus.SENT, campaign.getStatus());
        assertEquals(0, campaign.getRecipientsSent());
        assertEquals(0, campaign.getRecipientsFailed());
        assertEquals(1, campaign.getRecipientsTotal());
    }

    @Test
    void recipientFailureIsIsolatedAndCounted() {
        stubClaimWon(List.of(firstRecipient, secondRecipient));
        when(notificationService.notify(any()))
                .thenThrow(new IllegalStateException("expo down"))
                .thenAnswer(invocation -> Notification.builder()
                        .id(UUID.randomUUID()).channel(NotificationChannel.PUSH).build());

        service.dispatch(campaign.getId());

        assertEquals(CampaignStatus.SENT, campaign.getStatus());
        assertEquals(1, campaign.getRecipientsSent());
        assertEquals(1, campaign.getRecipientsFailed());
    }
}
