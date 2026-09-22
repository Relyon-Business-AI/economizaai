package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.DataShareConsent;
import com.relyon.economizaai.model.enums.ConsentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataShareConsentRepository extends JpaRepository<DataShareConsent, UUID> {

    // The grantor's inbox: requests awaiting their decision.
    List<DataShareConsent> findByGrantorIdAndStatus(UUID grantorId, ConsentStatus status);

    List<DataShareConsent> findByRequesterId(UUID requesterId);

    /** All consents the user granted/denied (any status) — used by the LGPD data export. */
    List<DataShareConsent> findByGrantorId(UUID grantorId);

    Optional<DataShareConsent> findByIdAndGrantorId(UUID id, UUID grantorId);
}
