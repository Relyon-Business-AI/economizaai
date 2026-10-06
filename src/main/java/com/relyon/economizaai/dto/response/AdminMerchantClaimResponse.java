package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantClaim;
import com.relyon.economizaai.model.enums.MerchantClaimStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Claim in the admin review queue — includes who is claiming. */
public record AdminMerchantClaimResponse(
        UUID id,
        UUID userId,
        String userEmail,
        String userName,
        String cnpj,
        String cnpjRoot,
        String companyName,
        String contactPhone,
        MerchantClaimStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {

    public static AdminMerchantClaimResponse from(MerchantClaim claim) {
        return new AdminMerchantClaimResponse(
                claim.getId(),
                claim.getUser().getId(),
                claim.getUser().getEmail(),
                claim.getUser().getName(),
                claim.getCnpj(),
                claim.getCnpjRoot(),
                claim.getCompanyName(),
                claim.getContactPhone(),
                claim.getStatus(),
                claim.getRejectionReason(),
                claim.getCreatedAt(),
                claim.getResolvedAt());
    }
}
