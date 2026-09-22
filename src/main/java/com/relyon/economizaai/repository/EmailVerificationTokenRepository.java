package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.EmailVerificationToken;
import com.relyon.economizaai.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    // The verification code is only unique per user (6 digits, hashed), so load the
    // user's single ACTIVE code and compare in the service — a lookup by typed code
    // would never find the row to count a wrong guess against.
    Optional<EmailVerificationToken> findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(User user);

    // Invalidate any still-open codes for a user before issuing a new one, so only
    // the most recent code works (standard OTP hygiene — a leaked older code is dead).
    @Modifying
    @Query("update EmailVerificationToken t set t.consumedAt = :now " +
           "where t.user = :user and t.consumedAt is null")
    void consumeAllActiveForUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
