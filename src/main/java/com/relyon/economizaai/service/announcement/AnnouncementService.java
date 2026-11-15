package com.relyon.economizaai.service.announcement;

import com.relyon.economizaai.dto.response.AnnouncementResponse;
import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.model.AnnouncementDismissal;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository;
import com.relyon.economizaai.repository.AnnouncementRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The announcement card on Home: which one a user sees right now, and closing it. Dismissing is
 * deliberately untransacted — the insert commits on its own, so a duplicate (a second tap, two
 * devices at once) lands in the catch instead of poisoning an outer transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementDismissalRepository dismissalRepository;
    private final UserRepository userRepository;

    /** The oldest-started announcement the user has not dismissed, or empty when there is nothing to show. */
    public Optional<AnnouncementResponse> currentFor(User user) {
        return announcementRepository.findVisibleTo(user.getId(), BrazilClock.nowDateTime(), Limit.of(1)).stream()
                .findFirst()
                .map(AnnouncementResponse::from);
    }

    public void dismiss(User user, UUID announcementId) {
        if (!announcementRepository.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
        if (dismissalRepository.existsByUserIdAndAnnouncementId(user.getId(), announcementId)) {
            log.debug("announcement.dismiss_repeated announcement={}", announcementId);
            return;
        }
        saveDismissal(user.getId(), announcementId);
    }

    private void saveDismissal(UUID userId, UUID announcementId) {
        try {
            dismissalRepository.saveAndFlush(AnnouncementDismissal.builder()
                    .user(userRepository.getReferenceById(userId))
                    .announcement(announcementRepository.getReferenceById(announcementId))
                    .build());
            log.info("announcement.dismissed announcement={}", announcementId);
        } catch (DataIntegrityViolationException concurrentDismissal) {
            log.debug("announcement.dismiss_concurrent announcement={}", announcementId);
        }
    }
}
