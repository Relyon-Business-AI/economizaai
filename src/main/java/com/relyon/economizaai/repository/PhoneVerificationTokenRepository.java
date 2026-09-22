package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PhoneVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PhoneVerificationTokenRepository extends JpaRepository<PhoneVerificationToken, UUID> {

    /** Most recent unconsumed OTP for the user — the one a verify attempt is checked against. */
    Optional<PhoneVerificationToken> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);
}
