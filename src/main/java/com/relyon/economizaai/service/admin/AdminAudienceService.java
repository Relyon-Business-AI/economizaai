package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SaveAudienceRequest;
import com.relyon.economizaai.dto.response.AudiencePreviewResponse;
import com.relyon.economizaai.dto.response.AudienceResponse;
import com.relyon.economizaai.exception.AudienceInUseException;
import com.relyon.economizaai.exception.AudienceNameConflictException;
import com.relyon.economizaai.exception.BuiltInAudienceException;
import com.relyon.economizaai.exception.NotificationAudienceNotFoundException;
import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationAudienceRepository;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + live resolution for campaign audiences. An audience is a filter
 * definition, never a frozen user list — counts and previews are computed
 * against the current user base on every call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAudienceService {

    private static final int PREVIEW_SAMPLE_SIZE = 10;

    private final NotificationAudienceRepository audienceRepository;
    private final NotificationCampaignRepository campaignRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AudienceResponse> list() {
        return audienceRepository.findAllByOrderByBuiltInDescNameAsc().stream()
                .map(audience -> AudienceResponse.from(audience, matchCount(audience)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AudienceResponse get(UUID audienceId) {
        var audience = requireAudience(audienceId);
        return AudienceResponse.from(audience, matchCount(audience));
    }

    @Transactional
    public AudienceResponse create(SaveAudienceRequest request) {
        if (audienceRepository.existsByNameIgnoreCase(request.name())) {
            throw new AudienceNameConflictException(request.name());
        }
        var audience = applyFilters(NotificationAudience.builder().build(), request);
        var saved = audienceRepository.save(audience);
        log.info("admin.audience.created id={} name={}", saved.getId(), saved.getName());
        return AudienceResponse.from(saved, matchCount(saved));
    }

    @Transactional
    public AudienceResponse update(UUID audienceId, SaveAudienceRequest request) {
        var audience = requireAudience(audienceId);
        if (audience.isBuiltIn()) throw new BuiltInAudienceException();
        if (!audience.getName().equalsIgnoreCase(request.name())
                && audienceRepository.existsByNameIgnoreCase(request.name())) {
            throw new AudienceNameConflictException(request.name());
        }
        var saved = audienceRepository.save(applyFilters(audience, request));
        log.info("admin.audience.updated id={} name={}", saved.getId(), saved.getName());
        return AudienceResponse.from(saved, matchCount(saved));
    }

    @Transactional
    public void delete(UUID audienceId) {
        var audience = requireAudience(audienceId);
        if (audience.isBuiltIn()) throw new BuiltInAudienceException();
        if (campaignRepository.existsByAudienceId(audienceId)) {
            throw new AudienceInUseException(audience.getName());
        }
        audienceRepository.delete(audience);
        log.info("admin.audience.deleted id={} name={}", audienceId, audience.getName());
    }

    @Transactional(readOnly = true)
    public AudiencePreviewResponse preview(UUID audienceId) {
        var audience = requireAudience(audienceId);
        var specification = AudienceSpecifications.toSpecification(audience);
        var sampleEmails = userRepository
                .findAll(specification, PageRequest.of(0, PREVIEW_SAMPLE_SIZE, Sort.by("email")))
                .map(User::getEmail)
                .getContent();
        return new AudiencePreviewResponse(userRepository.count(specification), sampleEmails);
    }

    /** The concrete recipient list an audience resolves to right now (used by dispatch). */
    @Transactional(readOnly = true)
    public List<User> resolveUsers(NotificationAudience audience) {
        return userRepository.findAll(AudienceSpecifications.toSpecification(audience));
    }

    private long matchCount(NotificationAudience audience) {
        return userRepository.count(AudienceSpecifications.toSpecification(audience));
    }

    private NotificationAudience requireAudience(UUID audienceId) {
        return audienceRepository.findById(audienceId)
                .orElseThrow(() -> new NotificationAudienceNotFoundException(String.valueOf(audienceId)));
    }

    private NotificationAudience applyFilters(NotificationAudience audience, SaveAudienceRequest request) {
        audience.setName(request.name());
        audience.setDescription(request.description());
        audience.setRole(request.role());
        audience.setSubscriptionTier(request.subscriptionTier());
        audience.setLocale(request.locale());
        audience.setHasPushToken(request.hasPushToken());
        audience.setRegisteredWithinDays(request.registeredWithinDays());
        audience.setActiveWithinDays(request.activeWithinDays());
        return audience;
    }
}
