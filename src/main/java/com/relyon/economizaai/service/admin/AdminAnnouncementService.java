package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SaveAnnouncementRequest;
import com.relyon.economizaai.dto.response.AdminAnnouncementResponse;
import com.relyon.economizaai.exception.AnnouncementNotFoundException;
import com.relyon.economizaai.exception.InvalidAnnouncementException;
import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository;
import com.relyon.economizaai.repository.AnnouncementDismissalRepository.DismissalTally;
import com.relyon.economizaai.repository.AnnouncementRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Admin CRUD of the in-app announcements, with how many users dismissed each one. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementDismissalRepository dismissalRepository;

    @Transactional(readOnly = true)
    public List<AdminAnnouncementResponse> list() {
        var announcements = announcementRepository.findAllByOrderByCreatedAtDesc();
        var dismissalCounts = dismissalCountsOf(announcements);
        var brasiliaNow = BrazilClock.nowDateTime();
        return announcements.stream()
                .map(announcement -> AdminAnnouncementResponse.from(announcement, brasiliaNow,
                        dismissalCounts.getOrDefault(announcement.getId(), 0L)))
                .toList();
    }

    @Transactional
    public AdminAnnouncementResponse create(SaveAnnouncementRequest request) {
        var brasiliaNow = BrazilClock.nowDateTime();
        validate(request, brasiliaNow);
        var saved = announcementRepository.save(applyContent(Announcement.builder().build(), request, brasiliaNow));
        log.info("admin.announcement.created id={} kind={} status={} startsAt={} endsAt={}",
                saved.getId(), saved.getKind(), saved.statusAt(brasiliaNow), saved.getStartsAt(), saved.getEndsAt());
        return AdminAnnouncementResponse.from(saved, brasiliaNow, 0L);
    }

    @Transactional
    public AdminAnnouncementResponse update(UUID announcementId, SaveAnnouncementRequest request) {
        var announcement = requireAnnouncement(announcementId);
        var brasiliaNow = BrazilClock.nowDateTime();
        validate(request, brasiliaNow);
        var saved = announcementRepository.save(applyContent(announcement, request, brasiliaNow));
        log.info("admin.announcement.updated id={} kind={} status={} startsAt={} endsAt={}",
                saved.getId(), saved.getKind(), saved.statusAt(brasiliaNow), saved.getStartsAt(), saved.getEndsAt());
        return AdminAnnouncementResponse.from(saved, brasiliaNow,
                dismissalCountsOf(List.of(saved)).getOrDefault(announcementId, 0L));
    }

    @Transactional
    public void delete(UUID announcementId) {
        announcementRepository.delete(requireAnnouncement(announcementId));
        log.info("admin.announcement.deleted id={}", announcementId);
    }

    private Announcement requireAnnouncement(UUID announcementId) {
        return announcementRepository.findById(announcementId).orElseThrow(AnnouncementNotFoundException::new);
    }

    private void validate(SaveAnnouncementRequest request, LocalDateTime brasiliaNow) {
        if (hasText(request.actionLabel()) != (request.destination() != null)) {
            throw InvalidAnnouncementException.incompleteAction();
        }
        if (request.endsAt() != null && !request.endsAt().isAfter(startsAtOf(request, brasiliaNow))) {
            throw InvalidAnnouncementException.endsBeforeItStarts();
        }
    }

    private Announcement applyContent(Announcement announcement, SaveAnnouncementRequest request, LocalDateTime brasiliaNow) {
        announcement.setKind(request.kind());
        announcement.setTitle(request.title().trim());
        announcement.setBody(trimmedOrNull(request.body()));
        announcement.setActionLabel(trimmedOrNull(request.actionLabel()));
        announcement.setDestination(request.destination());
        announcement.setStartsAt(startsAtOf(request, brasiliaNow));
        announcement.setEndsAt(request.endsAt());
        announcement.setActive(request.active() == null || request.active());
        return announcement;
    }

    private Map<UUID, Long> dismissalCountsOf(List<Announcement> announcements) {
        if (announcements.isEmpty()) return Map.of();
        var announcementIds = announcements.stream().map(Announcement::getId).toList();
        return dismissalRepository.tallyByAnnouncements(announcementIds).stream()
                .collect(Collectors.toMap(DismissalTally::getAnnouncementId, DismissalTally::getDismissals));
    }

    private static LocalDateTime startsAtOf(SaveAnnouncementRequest request, LocalDateTime brasiliaNow) {
        return request.startsAt() != null ? request.startsAt() : brasiliaNow;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String trimmedOrNull(String text) {
        return hasText(text) ? text.trim() : null;
    }
}
