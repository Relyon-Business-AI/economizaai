package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Narrows the deals list to the ones a notification announced: tapping "X mais barato e mais 2
 * ofertas" should open those three, not every deal the household has. The announced deals are
 * the {@code SENT} events the digest recorded with the notification id (product + market).
 */
@Service
@RequiredArgsConstructor
public class NotifiedDealsService {

    private final NotificationEventRepository eventRepository;

    /**
     * The deals of {@code currentDeals} that the notification announced to this user, in the same
     * order. A deal that is no longer valid is simply absent; a notification of someone else, or
     * one that announced no deal, yields an empty list.
     */
    @Transactional(readOnly = true)
    public List<DealResponse> onlyAnnouncedBy(User user, UUID notificationId, List<DealResponse> currentDeals) {
        var announced = eventRepository.findSentByNotification(user.getId(), notificationId).stream()
                .map(event -> new AnnouncedDeal(event.getProductId(), event.getMarketCnpj()))
                .collect(Collectors.toSet());
        return currentDeals.stream()
                .filter(deal -> announced.contains(new AnnouncedDeal(deal.productId(), deal.marketCnpj())))
                .toList();
    }

    private record AnnouncedDeal(UUID productId, String marketCnpj) {
    }
}
