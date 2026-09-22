package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PasswordResetToken;
import com.relyon.economizaai.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByToken(String token);

    // The reset code is only unique per user (6 digits), so look it up scoped to the
    // user. Newest first so a re-request supersedes an older still-valid code.
    Optional<PasswordResetToken> findFirstByUserAndTokenOrderByCreatedAtDesc(User user, String token);

    // Brute-force guard: load the user's single ACTIVE code regardless of what code
    // the caller typed, so failed attempts can be counted against it (a lookup by
    // the typed code would never find the row to increment on a wrong guess).
    Optional<PasswordResetToken> findFirstByUserAndConsumedAtIsNullOrderByCreatedAtDesc(User user);

    // Invalidate any still-open codes for a user before issuing a new one, so only the
    // most recent code works (standard OTP hygiene — a leaked older code is dead).
    @Modifying
    @Query("update PasswordResetToken t set t.consumedAt = :now " +
           "where t.user = :user and t.consumedAt is null")
    void consumeAllActiveForUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
