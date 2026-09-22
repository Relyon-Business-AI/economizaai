package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.InvalidConsentRequestException;
import com.relyon.economizaai.model.DataShareConsent;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ConsentStatus;
import com.relyon.economizaai.model.enums.LeaveScope;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.DataShareConsentRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mutual consent for taking/keeping another person's scanned data on split (Phase 2).
 *
 * <p>When a leaver requests {@code BOTH} (their data + the partner's), we do NOT copy
 * the partner's data immediately. Instead we record a PENDING request to the partner
 * (grantor) and notify them. If they APPROVE, the grantor's data is copied into the
 * requester's destination household; if DENIED/EXPIRED, nothing crosses over. The
 * leave itself never blocks — the leaver always gets their OWN data right away.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataShareConsentService {

    private static final int CONSENT_TTL_DAYS = 14;

    private final DataShareConsentRepository consentRepository;
    private final UserRepository userRepository;
    private final HouseholdMergeService mergeService;
    private final NotificationService notificationService;

    /**
     * Record a request for {@code requester} to take {@code grantor}'s data from
     * {@code sharedHousehold} into {@code destination}. Notifies the grantor. No data
     * moves yet — that waits for approval.
     */
    @Transactional
    public DataShareConsent request(User requester, User grantor, Household sharedHousehold,
                                    Household destination, LeaveScope scope) {
        if (requester.getId().equals(grantor.getId())) {
            throw new InvalidConsentRequestException("cannot request consent from yourself");
        }
        var consent = consentRepository.save(DataShareConsent.builder()
                .requester(requester)
                .grantor(grantor)
                .household(sharedHousehold)
                .destinationHousehold(destination)
                .scope(scope)
                .status(ConsentStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(CONSENT_TTL_DAYS))
                .build());
        notificationService.notify(new NotificationPayload(grantor, NotificationType.SYSTEM,
                "Pedido de dados compartilhados",
                requester.getName() + " quer levar uma cópia dos seus dados ao sair do domicílio. "
                        + "Você pode permitir ou recusar.", null));
        log.info("consent.requested id={} requester={} grantor={} scope={}",
                consent.getId(), LogMasker.email(requester.getEmail()), LogMasker.email(grantor.getEmail()), scope);
        return consent;
    }

    /** The grantor's pending requests (their decision inbox). */
    @Transactional(readOnly = true)
    public List<DataShareConsent> pendingForGrantor(User grantor) {
        return consentRepository.findByGrantorIdAndStatus(grantor.getId(), ConsentStatus.PENDING);
    }

    @Transactional
    public DataShareConsent approve(User grantor, java.util.UUID consentId) {
        var consent = loadDecidable(grantor, consentId);
        consent.setStatus(ConsentStatus.APPROVED);
        consent.setResolvedAt(LocalDateTime.now());
        consentRepository.save(consent);
        // Copy the grantor's data from the shared household into the requester's
        // destination. The grantor keeps their originals (copy, not move).
        var copied = mergeService.copyUserData(
                consent.getGrantor().getId(), consent.getHousehold(), consent.getDestinationHousehold());
        notificationService.notify(new NotificationPayload(consent.getRequester(), NotificationType.SYSTEM,
                "Dados compartilhados aprovados",
                consent.getGrantor().getName() + " permitiu que você levasse uma cópia dos dados.", null));
        log.info("consent.approved id={} copied={}", consent.getId(), copied);
        return consent;
    }

    @Transactional
    public DataShareConsent deny(User grantor, java.util.UUID consentId) {
        var consent = loadDecidable(grantor, consentId);
        consent.setStatus(ConsentStatus.DENIED);
        consent.setResolvedAt(LocalDateTime.now());
        consentRepository.save(consent);
        notificationService.notify(new NotificationPayload(consent.getRequester(), NotificationType.SYSTEM,
                "Pedido de dados recusado",
                consent.getGrantor().getName() + " não permitiu o compartilhamento dos dados.", null));
        log.info("consent.denied id={}", consent.getId());
        return consent;
    }

    // Load a consent the grantor is allowed to decide: must be theirs, still PENDING,
    // and not expired (an expired one is flipped to EXPIRED and rejected).
    private DataShareConsent loadDecidable(User grantor, java.util.UUID consentId) {
        var consent = consentRepository.findByIdAndGrantorId(consentId, grantor.getId())
                .orElseThrow(() -> new InvalidConsentRequestException("consent not found"));
        if (consent.getStatus() != ConsentStatus.PENDING) {
            throw new InvalidConsentRequestException("consent already " + consent.getStatus());
        }
        if (consent.getExpiresAt().isBefore(LocalDateTime.now())) {
            consent.setStatus(ConsentStatus.EXPIRED);
            consentRepository.save(consent);
            throw new InvalidConsentRequestException("consent expired");
        }
        return consent;
    }
}
