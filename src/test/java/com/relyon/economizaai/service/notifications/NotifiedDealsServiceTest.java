package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifiedDealsServiceTest {

    private static final String ANNOUNCED_MARKET = "12345678000199";
    private static final String OTHER_MARKET = "98765432000188";

    @Mock private NotificationEventRepository eventRepository;
    @InjectMocks private NotifiedDealsService notifiedDealsService;

    private final User recipient = User.builder().id(UUID.randomUUID()).email("recipient@economizaai.app").build();
    private final UUID notificationId = UUID.randomUUID();

    private DealResponse deal(UUID productId, String marketCnpj) {
        return new DealResponse(productId, "Chuchu kg", null, "PRODUCE", marketCnpj, "Mercado",
                new BigDecimal("3.90"), new BigDecimal("5.70"),
                new BigDecimal("1.80"), new BigDecimal("31.58"), new BigDecimal("0.3158"),
                3L, null, true, LocalDateTime.of(2026, 11, 17, 0, 0));
    }

    private NotificationEvent sentEvent(UUID productId, String marketCnpj) {
        return NotificationEvent.builder()
                .user(recipient).eventType(NotificationEventType.SENT)
                .notificationId(notificationId).productId(productId).marketCnpj(marketCnpj)
                .build();
    }

    private void announced(NotificationEvent... events) {
        when(eventRepository.findSentByNotification(recipient.getId(), notificationId)).thenReturn(List.of(events));
    }

    @Test
    void keepsOnlyTheAnnouncedDealsInTheOriginalOrder() {
        var chuchu = UUID.randomUUID();
        var tomato = UUID.randomUUID();
        var notAnnounced = UUID.randomUUID();
        announced(sentEvent(tomato, ANNOUNCED_MARKET), sentEvent(chuchu, ANNOUNCED_MARKET));
        var chuchuDeal = deal(chuchu, ANNOUNCED_MARKET);
        var tomatoDeal = deal(tomato, ANNOUNCED_MARKET);

        var kept = notifiedDealsService.onlyAnnouncedBy(recipient, notificationId,
                List.of(chuchuDeal, deal(notAnnounced, ANNOUNCED_MARKET), tomatoDeal));

        assertEquals(List.of(chuchuDeal, tomatoDeal), kept);
    }

    @Test
    void sameProductAtAnotherMarketIsNotTheAnnouncedDeal() {
        var chuchu = UUID.randomUUID();
        announced(sentEvent(chuchu, ANNOUNCED_MARKET));

        var kept = notifiedDealsService.onlyAnnouncedBy(recipient, notificationId,
                List.of(deal(chuchu, OTHER_MARKET)));

        assertTrue(kept.isEmpty());
    }

    @Test
    void announcedDealThatIsNoLongerValidIsAbsent() {
        announced(sentEvent(UUID.randomUUID(), ANNOUNCED_MARKET));

        var kept = notifiedDealsService.onlyAnnouncedBy(recipient, notificationId,
                List.of(deal(UUID.randomUUID(), ANNOUNCED_MARKET)));

        assertTrue(kept.isEmpty());
    }

    @Test
    void notificationThatAnnouncedNoDealKeepsNothing() {
        announced();

        var kept = notifiedDealsService.onlyAnnouncedBy(recipient, notificationId,
                List.of(deal(UUID.randomUUID(), ANNOUNCED_MARKET)));

        assertTrue(kept.isEmpty());
    }
}
