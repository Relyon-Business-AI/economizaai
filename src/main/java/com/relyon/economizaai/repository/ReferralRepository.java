package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /** The referral that brought this account in, with the inviter loaded (it gets notified/rewarded). */
    @Query("SELECT referral FROM Referral referral JOIN FETCH referral.inviter WHERE referral.invited.id = :invitedId")
    Optional<Referral> findByInvitedIdWithInviter(@Param("invitedId") UUID invitedId);

    boolean existsByInvitedId(UUID invitedId);

    boolean existsByInviterIdAndInvitedId(UUID inviterId, UUID invitedId);

    long countByInviterId(UUID inviterId);

    long countByInviterIdAndQualifiedAtIsNotNull(UUID inviterId);
}
