package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MerchantClaim;
import com.relyon.economizaai.model.enums.MerchantClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantClaimRepository extends JpaRepository<MerchantClaim, UUID> {

    List<MerchantClaim> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<MerchantClaim> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndCnpjRootAndStatusIn(UUID userId, String cnpjRoot,
                                                 List<MerchantClaimStatus> statuses);

    List<MerchantClaim> findAllByStatusOrderByCreatedAtAsc(MerchantClaimStatus status);

    /** The user's most recent claim for the chain that ended in {@code status} after {@code since}. */
    Optional<MerchantClaim> findFirstByUserIdAndCnpjRootAndStatusAndResolvedAtAfterOrderByResolvedAtDesc(
            UUID userId, String cnpjRoot, MerchantClaimStatus status, LocalDateTime since);
}
