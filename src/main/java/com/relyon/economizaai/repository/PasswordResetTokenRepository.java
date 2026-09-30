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

    // Atomic in-database increment — concurrent wrong guesses can't lose a count
    // to a read-modify-write race, and the caller commits it via noRollbackFor.
    @Modifying
    @Query("update PasswordResetToken t set t.attempts = t.attempts + 1 where t.id = :id")
    void incrementAttempts(@Param("id") UUID id);
}
