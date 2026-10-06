package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.MerchantClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Self-serve "sou este mercado" claim (docs/MERCHANT_ACCOUNTS.md, Fase 1.5).
 * Verified by a code sent to the company e-mail registered at the Receita
 * (BrasilAPI lookup); claims without a usable company e-mail fall back to the
 * admin review queue. Only the code's SHA-256 hash is persisted.
 */
@Entity
@Table(name = "merchant_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MerchantClaim extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 14)
    private String cnpj;

    @Column(name = "cnpj_root", nullable = false, length = 8)
    private String cnpjRoot;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantClaimStatus status;

    @Column(name = "verification_code_hash", length = 64)
    private String verificationCodeHash;

    /** Masked company e-mail shown back to the user ("fi***@mercado.com.br"). */
    @Column(name = "verification_email_masked", length = 255)
    private String verificationEmailMasked;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    @Column(name = "code_attempts", nullable = false)
    @Builder.Default
    private int codeAttempts = 0;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** Admin who resolved it; null when auto-approved by the e-mail code. */
    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public boolean isOpen() {
        return status == MerchantClaimStatus.AWAITING_CODE || status == MerchantClaimStatus.PENDING_REVIEW;
    }
}
