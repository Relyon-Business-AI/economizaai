package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.model.AnnouncementDismissal;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository.DismissalTally;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class AnnouncementRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.NOVEMBER, 15, 12, 0);

    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private AnnouncementDismissalRepository dismissalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private EntityManager entityManager;

    private User reader;
    private User otherReader;

    @BeforeEach
    void setUp() {
        reader = createUser("ANN1");
        otherReader = createUser("ANN2");
    }

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode(suffix).build());
        return userRepository.save(User.builder()
                .name("Reader " + suffix).email("announcement-" + suffix.toLowerCase() + "@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Announcement saveAnnouncement(String title, LocalDateTime startsAt, LocalDateTime endsAt, boolean active) {
        return announcementRepository.save(Announcement.builder()
                .kind(AnnouncementKind.NEWS).title(title)
                .startsAt(startsAt).endsAt(endsAt).active(active)
                .build());
    }

    private Announcement saveLive(String title, LocalDateTime startsAt) {
        return saveAnnouncement(title, startsAt, null, true);
    }

    private void dismiss(User user, Announcement announcement) {
        dismissalRepository.saveAndFlush(AnnouncementDismissal.builder().user(user).announcement(announcement).build());
    }

    /** created_at is not updatable through the entity, so the test rewrites the column directly. */
    private void backdateCreation(Announcement announcement, LocalDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE announcements SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", announcement.getId())
                .executeUpdate();
    }

    private List<String> titlesVisibleTo(User user) {
        return announcementRepository.findVisibleTo(user.getId(), NOW, Limit.unlimited()).stream()
                .map(Announcement::getTitle)
                .toList();
    }

    @Test
    void nothingIsVisibleWhenThereAreNoAnnouncements() {
        assertTrue(titlesVisibleTo(reader).isEmpty());
    }

    @Test
    void aLiveAnnouncementIsVisible() {
        saveLive("live", NOW.minusDays(1));

        assertEquals(List.of("live"), titlesVisibleTo(reader));
    }

    @Test
    void anAnnouncementStartingExactlyNowIsVisible() {
        saveLive("starting now", NOW);

        assertEquals(List.of("starting now"), titlesVisibleTo(reader));
    }

    @Test
    void aScheduledAnnouncementIsNotVisibleYet() {
        saveLive("scheduled", NOW.plusMinutes(1));

        assertTrue(titlesVisibleTo(reader).isEmpty());
    }

    @Test
    void anEndedAnnouncementIsNotVisible() {
        saveAnnouncement("ended", NOW.minusDays(3), NOW.minusDays(1), true);
        saveAnnouncement("ending now", NOW.minusDays(3), NOW, true);

        assertTrue(titlesVisibleTo(reader).isEmpty());
    }

    @Test
    void anAnnouncementStillInsideItsWindowIsVisible() {
        saveAnnouncement("ends tomorrow", NOW.minusDays(1), NOW.plusDays(1), true);

        assertEquals(List.of("ends tomorrow"), titlesVisibleTo(reader));
    }

    @Test
    void anInactiveAnnouncementIsNotVisible() {
        saveAnnouncement("switched off", NOW.minusDays(1), null, false);

        assertTrue(titlesVisibleTo(reader).isEmpty());
    }

    @Test
    void aDismissedAnnouncementIsHiddenOnlyFromWhoDismissedIt() {
        var announcement = saveLive("dismissed by one", NOW.minusDays(1));
        dismiss(reader, announcement);

        assertTrue(titlesVisibleTo(reader).isEmpty());
        assertEquals(List.of("dismissed by one"), titlesVisibleTo(otherReader));
    }

    @Test
    void theOldestStartComesFirstAndTheNextOneFollowsADismissal() {
        saveLive("newer", NOW.minusDays(1));
        var older = saveLive("older", NOW.minusDays(5));

        assertEquals(List.of("older", "newer"), titlesVisibleTo(reader));
        assertEquals("older", announcementRepository.findVisibleTo(reader.getId(), NOW, Limit.of(1)).getFirst().getTitle());

        dismiss(reader, older);

        assertEquals("newer", announcementRepository.findVisibleTo(reader.getId(), NOW, Limit.of(1)).getFirst().getTitle());
    }

    @Test
    void theSameUserCannotDismissTheSameAnnouncementTwice() {
        var announcement = saveLive("once", NOW.minusDays(1));
        dismiss(reader, announcement);

        assertThrows(DataIntegrityViolationException.class, () -> dismiss(reader, announcement));
    }

    @Test
    void existsByUserAndAnnouncementTellsWhoDismissed() {
        var announcement = saveLive("once", NOW.minusDays(1));
        dismiss(reader, announcement);

        assertTrue(dismissalRepository.existsByUserIdAndAnnouncementId(reader.getId(), announcement.getId()));
        assertFalse(dismissalRepository.existsByUserIdAndAnnouncementId(otherReader.getId(), announcement.getId()));
    }

    @Test
    void tallyCountsDismissalsPerAnnouncementAndSkipsTheNeverDismissed() {
        var dismissedByBoth = saveLive("both", NOW.minusDays(3));
        var dismissedByOne = saveLive("one", NOW.minusDays(2));
        var neverDismissed = saveLive("none", NOW.minusDays(1));
        dismiss(reader, dismissedByBoth);
        dismiss(otherReader, dismissedByBoth);
        dismiss(reader, dismissedByOne);

        Map<UUID, Long> dismissalsByAnnouncement = dismissalRepository
                .tallyByAnnouncements(List.of(dismissedByBoth.getId(), dismissedByOne.getId(), neverDismissed.getId())).stream()
                .collect(Collectors.toMap(DismissalTally::getAnnouncementId, DismissalTally::getDismissals));

        assertEquals(Map.of(dismissedByBoth.getId(), 2L, dismissedByOne.getId(), 1L), dismissalsByAnnouncement);
    }

    @Test
    void listForAdminIsNewestCreatedFirst() {
        var first = saveLive("first", NOW);
        var second = saveLive("second", NOW);
        announcementRepository.flush();
        backdateCreation(first, NOW.minusDays(2));
        backdateCreation(second, NOW.minusDays(1));
        entityManager.clear();

        var titles = announcementRepository.findAllByOrderByCreatedAtDesc().stream().map(Announcement::getTitle).toList();

        assertEquals(List.of("second", "first"), titles);
    }

    @Test
    void deletingAnAnnouncementTakesItsDismissalsAlong() {
        var announcement = saveLive("to delete", NOW.minusDays(1));
        dismiss(reader, announcement);
        entityManager.clear();

        announcementRepository.deleteById(announcement.getId());
        announcementRepository.flush();
        entityManager.clear();

        assertEquals(0, dismissalRepository.count());
    }
}
