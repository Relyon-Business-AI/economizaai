package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.RefreshToken;
import com.relyon.economizaai.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    /**
     * Kills every still-usable token for the user in one statement — called on
     * password change/reset so a compromised session can't outlive the recovery.
     */
    @Modifying
    @Query("UPDATE RefreshToken refreshToken SET refreshToken.revokedAt = CURRENT_TIMESTAMP "
            + "WHERE refreshToken.user = :user AND refreshToken.revokedAt IS NULL AND refreshToken.consumedAt IS NULL")
    int revokeAllActiveForUser(User user);

    /**
     * Kills every still-usable token for the user EXCEPT the given family (the session that
     * asked for the change stays signed in). Tokens without a family are their own family.
     */
    @Modifying
    @Query("UPDATE RefreshToken refreshToken SET refreshToken.revokedAt = CURRENT_TIMESTAMP "
            + "WHERE refreshToken.user = :user AND refreshToken.revokedAt IS NULL AND refreshToken.consumedAt IS NULL "
            + "AND refreshToken.id <> :keptFamilyId "
            + "AND (refreshToken.familyId IS NULL OR refreshToken.familyId <> :keptFamilyId)")
    int revokeAllActiveForUserExceptFamily(User user, UUID keptFamilyId);

    /**
     * Revoga só os tokens ativos de UMA família (a cadeia de rotações de um login/dispositivo) —
     * a resposta a reuso detectado, que não deve derrubar as outras sessões do usuário.
     */
    @Modifying
    @Query("UPDATE RefreshToken refreshToken SET refreshToken.revokedAt = CURRENT_TIMESTAMP "
            + "WHERE refreshToken.familyId = :familyId AND refreshToken.revokedAt IS NULL AND refreshToken.consumedAt IS NULL")
    int revokeActiveForFamily(UUID familyId);
}
