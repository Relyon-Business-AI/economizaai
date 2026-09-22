package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ConsentStatus;
import com.relyon.economizaai.model.enums.LeaveScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A request for one user (requester) to take/keep data another user (grantor) scanned,
 * when leaving/splitting a household. The grantor must APPROVE before the data is
 * copied to the requester's destination household. See HouseholdMergeService /
 * DataShareConsentService.
 */
@Entity
@Table(name = "data_share_consents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class DataShareConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_user_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grantor_user_id", nullable = false)
    private User grantor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_household_id", nullable = false)
    private Household destinationHousehold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LeaveScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsentStatus status;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
