package com.relyon.economizaai.service.announcement;

import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.model.AnnouncementDismissal;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository;
import com.relyon.economizaai.repository.AnnouncementRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AnnouncementDismissalRepository dismissalRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private AnnouncementService service;

    private final User user = User.builder().id(UUID.randomUUID()).email("reader73412@economizaai.app").build();

    private static Announcement announcement() {
        return Announcement.builder()
                .id(UUID.randomUUID()).kind(AnnouncementKind.TIP).title("Compare mercados")
                .body("Veja onde sua lista sai mais barata.")
                .actionLabel("Ver mercados").destination(AnnouncementDestination.MARKETS)
                .startsAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Test
    void currentReturnsTheFirstVisibleAnnouncementAsACard() {
        var visible = announcement();
        when(announcementRepository.findVisibleTo(eq(user.getId()), any(LocalDateTime.class), eq(Limit.of(1))))
                .thenReturn(List.of(visible));

        var card = service.currentFor(user).orElseThrow();

        assertEquals(visible.getId(), card.id());
        assertEquals(AnnouncementKind.TIP, card.kind());
        assertEquals("Compare mercados", card.title());
        assertEquals("Veja onde sua lista sai mais barata.", card.body());
        assertEquals("Ver mercados", card.actionLabel());
        assertEquals(AnnouncementDestination.MARKETS, card.destination());
    }

    @Test
    void currentIsEmptyWhenNothingIsVisible() {
        when(announcementRepository.findVisibleTo(eq(user.getId()), any(LocalDateTime.class), eq(Limit.of(1))))
                .thenReturn(List.of());

        assertTrue(service.currentFor(user).isEmpty());
    }

    @Test
    void currentSelectsAtTheBrasiliaWallClock() {
        var momentCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(announcementRepository.findVisibleTo(eq(user.getId()), momentCaptor.capture(), eq(Limit.of(1))))
                .thenReturn(List.of());
        var before = BrazilClock.nowDateTime();

        service.currentFor(user);

        assertFalse(momentCaptor.getValue().isBefore(before));
        assertFalse(momentCaptor.getValue().isAfter(BrazilClock.nowDateTime()));
    }

    @Test
    void dismissRecordsTheUserAndTheAnnouncement() {
        var dismissed = announcement();
        var userReference = User.builder().id(user.getId()).build();
        when(announcementRepository.existsById(dismissed.getId())).thenReturn(true);
        when(dismissalRepository.existsByUserIdAndAnnouncementId(user.getId(), dismissed.getId())).thenReturn(false);
        when(userRepository.getReferenceById(user.getId())).thenReturn(userReference);
        when(announcementRepository.getReferenceById(dismissed.getId())).thenReturn(dismissed);

        service.dismiss(user, dismissed.getId());

        var dismissalCaptor = ArgumentCaptor.forClass(AnnouncementDismissal.class);
        verify(dismissalRepository).saveAndFlush(dismissalCaptor.capture());
        assertSame(userReference, dismissalCaptor.getValue().getUser());
        assertSame(dismissed, dismissalCaptor.getValue().getAnnouncement());
    }

    @Test
    void dismissingAgainIsANoOp() {
        var announcementId = UUID.randomUUID();
        when(announcementRepository.existsById(announcementId)).thenReturn(true);
        when(dismissalRepository.existsByUserIdAndAnnouncementId(user.getId(), announcementId)).thenReturn(true);

        service.dismiss(user, announcementId);

        verify(dismissalRepository, never()).saveAndFlush(any());
    }

    @Test
    void aConcurrentDismissalLosingTheUniquePairIsSwallowed() {
        var announcementId = UUID.randomUUID();
        when(announcementRepository.existsById(announcementId)).thenReturn(true);
        when(dismissalRepository.existsByUserIdAndAnnouncementId(user.getId(), announcementId)).thenReturn(false);
        when(dismissalRepository.saveAndFlush(any(AnnouncementDismissal.class)))
                .thenThrow(new DataIntegrityViolationException("uq_announcement_dismissals_user_announcement"));

        assertDoesNotThrow(() -> service.dismiss(user, announcementId));
    }

    @Test
    void dismissingAnUnknownAnnouncementIsNotFound() {
        var announcementId = UUID.randomUUID();
        when(announcementRepository.existsById(announcementId)).thenReturn(false);

        assertThrows(AnnouncementNotFoundException.class, () -> service.dismiss(user, announcementId));

        verify(dismissalRepository, never()).saveAndFlush(any());
    }
}
