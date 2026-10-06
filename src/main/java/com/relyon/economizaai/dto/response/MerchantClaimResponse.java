package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantClaim;
import com.relyon.economizaai.model.enums.MerchantClaimStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Claim as seen by its submitter. Never exposes the company e-mail unmasked. */
public record MerchantClaimResponse(
        UUID id,
        String cnpj,
        String cnpjRoot,
        String companyName,
        MerchantClaimStatus status,
        String verificationEmailMasked,
        LocalDateTime codeExpiresAt,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {

    public static MerchantClaimResponse from(MerchantClaim claim) {
        return new MerchantClaimResponse(
                claim.getId(),
                claim.getCnpj(),
                claim.getCnpjRoot(),
                claim.getCompanyName(),
                claim.getStatus(),
                claim.getVerificationEmailMasked(),
                claim.getCodeExpiresAt(),
                claim.getRejectionReason(),
                claim.getCreatedAt(),
                claim.getResolvedAt());
    }
}
