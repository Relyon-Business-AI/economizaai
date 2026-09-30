package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PhoneVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PhoneVerificationTokenRepository extends JpaRepository<PhoneVerificationToken, UUID> {

    /** Most recent unconsumed OTP for the user — the one a verify attempt is checked against. */
    Optional<PhoneVerificationToken> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    // Atomic in-database increment — concurrent wrong guesses can't lose a count
    // to a read-modify-write race, and the caller commits it via noRollbackFor.
    @Modifying
    @Query("update PhoneVerificationToken t set t.attempts = t.attempts + 1 where t.id = :id")
    void incrementAttempts(@Param("id") UUID id);
}
