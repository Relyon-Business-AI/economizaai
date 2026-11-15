package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SaveAnnouncementRequest;
import com.relyon.economizaai.dto.response.AdminAnnouncementResponse;
import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.exception.InvalidAnnouncementException;
import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.model.enums.AnnouncementStatus;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository.DismissalTally;
import com.relyon.economizaai.repository.AnnouncementRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AnnouncementDismissalRepository dismissalRepository;

    @InjectMocks private AdminAnnouncementService service;

    private static Announcement announcement(LocalDateTime startsAt, LocalDateTime endsAt, boolean active) {
        return Announcement.builder()
                .id(UUID.randomUUID()).kind(AnnouncementKind.NEWS).title("Novidade")
                .startsAt(startsAt).endsAt(endsAt).active(active)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static SaveAnnouncementRequest request(String actionLabel, AnnouncementDestination destination,
                                                   LocalDateTime startsAt, LocalDateTime endsAt, Boolean active) {
        return new SaveAnnouncementRequest(AnnouncementKind.TIP, "  Compare mercados  ", "  Veja onde sai mais barato.  ",
                actionLabel, destination, startsAt, endsAt, active);
    }

    private static DismissalTally tally(UUID announcementId, long dismissals) {
        return new DismissalTally() {
            @Override
            public UUID getAnnouncementId() {
                return announcementId;
            }

            @Override
            public long getDismissals() {
                return dismissals;
            }
        };
    }

    private void savingReturnsTheSameAnnouncement() {
        when(announcementRepository.save(any(Announcement.class)))
                .thenAnswer(invocation -> {
                    Announcement saved = invocation.getArgument(0);
                    if (saved.getId() == null) saved.setId(UUID.randomUUID());
                    return saved;
                });
    }

    // --- list ---

    @Test
    void listComputesEachStatusAndDismissalCount() {
        var now = BrazilClock.nowDateTime();
        var scheduled = announcement(now.plusDays(1), null, true);
        var live = announcement(now.minusDays(1), now.plusDays(1), true);
        var ended = announcement(now.minusDays(3), now.minusDays(1), true);
        var inactive = announcement(now.minusDays(1), null, false);
        when(announcementRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(scheduled, live, ended, inactive));
        when(dismissalRepository.tallyByAnnouncements(anyCollection())).thenReturn(List.of(tally(live.getId(), 7L)));

        var listed = service.list();

        assertEquals(List.of(AnnouncementStatus.SCHEDULED, AnnouncementStatus.LIVE, AnnouncementStatus.ENDED,
                AnnouncementStatus.INACTIVE), listed.stream().map(AdminAnnouncementResponse::status).toList());
        assertEquals(List.of(0L, 7L, 0L, 0L), listed.stream().map(AdminAnnouncementResponse::dismissalCount).toList());
        assertEquals(List.of(scheduled.getId(), live.getId(), ended.getId(), inactive.getId()),
                listed.stream().map(AdminAnnouncementResponse::id).toList());
    }

    @Test
    void listCountsDismissalsInOneBatchQuery() {
        var now = BrazilClock.nowDateTime();
        var first = announcement(now.minusDays(1), null, true);
        var second = announcement(now.minusDays(2), null, true);
        when(announcementRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(first, second));
        when(dismissalRepository.tallyByAnnouncements(List.of(first.getId(), second.getId()))).thenReturn(List.of());

        service.list();

        verify(dismissalRepository).tallyByAnnouncements(List.of(first.getId(), second.getId()));
    }

    @Test
    void anEmptyListDoesNotQueryDismissals() {
        when(announcementRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertTrue(service.list().isEmpty());

        verify(dismissalRepository, never()).tallyByAnnouncements(anyCollection());
    }

    @Test
    void anInactiveAnnouncementIsInactiveEvenWhenScheduledOrEnded() {
        var now = BrazilClock.nowDateTime();

        assertEquals(AnnouncementStatus.INACTIVE, announcement(now.plusDays(1), null, false).statusAt(now));
        assertEquals(AnnouncementStatus.INACTIVE, announcement(now.minusDays(3), now.minusDays(1), false).statusAt(now));
    }

    @Test
    void anAnnouncementEndsAtItsExclusiveEnd() {
        var now = BrazilClock.nowDateTime();

        assertEquals(AnnouncementStatus.ENDED, announcement(now.minusDays(1), now, true).statusAt(now));
        assertEquals(AnnouncementStatus.LIVE, announcement(now, null, true).statusAt(now));
    }

    // --- create ---

    @Test
    void createDefaultsToStartingNowAndActive() {
        savingReturnsTheSameAnnouncement();
        var before = BrazilClock.nowDateTime();

        var created = service.create(request(null, null, null, null, null));

        assertFalse(created.startsAt().isBefore(before));
        assertFalse(created.startsAt().isAfter(BrazilClock.nowDateTime()));
        assertNull(created.endsAt());
        assertTrue(created.active());
        assertEquals(AnnouncementStatus.LIVE, created.status());
        assertEquals(0L, created.dismissalCount());
    }

    @Test
    void createTrimsTheTextsAndKeepsTheAction() {
        savingReturnsTheSameAnnouncement();

        var created = service.create(request(" Ver mercados ", AnnouncementDestination.MARKETS, null, null, true));

        assertEquals(AnnouncementKind.TIP, created.kind());
        assertEquals("Compare mercados", created.title());
        assertEquals("Veja onde sai mais barato.", created.body());
        assertEquals("Ver mercados", created.actionLabel());
        assertEquals(AnnouncementDestination.MARKETS, created.destination());
    }

    @Test
    void createStoresBlankOptionalTextsAsNull() {
        savingReturnsTheSameAnnouncement();
        var blankTexts = new SaveAnnouncementRequest(AnnouncementKind.NOTICE, "Aviso", "   ", "  ", null, null, null, null);

        var created = service.create(blankTexts);

        assertNull(created.body());
        assertNull(created.actionLabel());
        assertNull(created.destination());
    }

    @Test
    void createInTheFutureIsScheduled() {
        savingReturnsTheSameAnnouncement();
        var startsAt = BrazilClock.nowDateTime().plusDays(2);

        var created = service.create(request(null, null, startsAt, startsAt.plusDays(7), null));

        assertEquals(startsAt, created.startsAt());
        assertEquals(startsAt.plusDays(7), created.endsAt());
        assertEquals(AnnouncementStatus.SCHEDULED, created.status());
    }

    @Test
    void createSwitchedOffIsInactive() {
        savingReturnsTheSameAnnouncement();

        var created = service.create(request(null, null, null, null, false));

        assertFalse(created.active());
        assertEquals(AnnouncementStatus.INACTIVE, created.status());
    }

    @Test
    void anActionLabelWithoutDestinationIsRejected() {
        assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request("Ver mercados", null, null, null, null)));

        verify(announcementRepository, never()).save(any());
    }

    @Test
    void aDestinationWithoutActionLabelIsRejected() {
        var missingLabel = assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request(null, AnnouncementDestination.DEALS, null, null, null)));
        var blankLabel = assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request("   ", AnnouncementDestination.DEALS, null, null, null)));

        assertEquals("announcement.action.incomplete", missingLabel.getMessageKey());
        assertEquals("announcement.action.incomplete", blankLabel.getMessageKey());
        verify(announcementRepository, never()).save(any());
    }

    @Test
    void anEndNotAfterTheStartIsRejected() {
        var startsAt = BrazilClock.nowDateTime().plusDays(1);

        var endsBefore = assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request(null, null, startsAt, startsAt.minusHours(1), null)));
        assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request(null, null, startsAt, startsAt, null)));

        assertEquals("announcement.window.invalid", endsBefore.getMessageKey());
        verify(announcementRepository, never()).save(any());
    }

    @Test
    void anEndInThePastIsRejectedWhenTheStartDefaultsToNow() {
        assertThrows(InvalidAnnouncementException.class,
                () -> service.create(request(null, null, null, BrazilClock.nowDateTime().minusMinutes(1), null)));
    }

    // --- update ---

    @Test
    void updateReplacesEveryFieldAndKeepsTheDismissalCount() {
        var now = BrazilClock.nowDateTime();
        var existing = Announcement.builder()
                .id(UUID.randomUUID()).kind(AnnouncementKind.NEWS).title("Antigo").body("Corpo antigo")
                .actionLabel("Abrir").destination(AnnouncementDestination.PREMIUM)
                .startsAt(now.minusDays(5)).endsAt(now.plusDays(5)).active(true)
                .createdAt(LocalDateTime.now())
                .build();
        when(announcementRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        savingReturnsTheSameAnnouncement();
        when(dismissalRepository.tallyByAnnouncements(List.of(existing.getId())))
                .thenReturn(List.of(tally(existing.getId(), 3L)));
        var replacement = new SaveAnnouncementRequest(AnnouncementKind.NOTICE, "Novo", null, null, null,
                now.minusDays(1), null, false);

        var updated = service.update(existing.getId(), replacement);

        assertEquals(existing.getId(), updated.id());
        assertEquals(AnnouncementKind.NOTICE, updated.kind());
        assertEquals("Novo", updated.title());
        assertNull(updated.body());
        assertNull(updated.actionLabel());
        assertNull(updated.destination());
        assertEquals(now.minusDays(1), updated.startsAt());
        assertNull(updated.endsAt());
        assertFalse(updated.active());
        assertEquals(AnnouncementStatus.INACTIVE, updated.status());
        assertEquals(3L, updated.dismissalCount());
    }

    @Test
    void updateValidatesLikeCreate() {
        var existing = announcement(BrazilClock.nowDateTime().minusDays(1), null, true);
        when(announcementRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThrows(InvalidAnnouncementException.class,
                () -> service.update(existing.getId(), request("Ver ofertas", null, null, null, null)));

        verify(announcementRepository, never()).save(any());
    }

    @Test
    void updatingAnUnknownAnnouncementIsNotFound() {
        var announcementId = UUID.randomUUID();
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertThrows(AnnouncementNotFoundException.class,
                () -> service.update(announcementId, request(null, null, null, null, null)));
    }

    // --- delete ---

    @Test
    void deleteRemovesTheAnnouncement() {
        var existing = announcement(BrazilClock.nowDateTime().minusDays(1), null, true);
        when(announcementRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.delete(existing.getId());

        verify(announcementRepository).delete(existing);
    }

    @Test
    void deletingAnUnknownAnnouncementIsNotFound() {
        var announcementId = UUID.randomUUID();
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertThrows(AnnouncementNotFoundException.class, () -> service.delete(announcementId));

        verify(announcementRepository, never()).delete(any(Announcement.class));
    }
}
