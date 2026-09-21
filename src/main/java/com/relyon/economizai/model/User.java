package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.DigestFrequency;
import com.relyon.economizai.model.enums.Platform;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class User extends BaseEntity implements UserDetails {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    // Preferred UI language ("pt"/"en"), captured from Accept-Language at
    // registration. Drives emails + push notifications (produced off-request).
    @Column(nullable = false, length = 5)
    @Builder.Default
    private String locale = "pt";

    // Nullable: social-login (Google/Apple) users have no local password.
    @Column(nullable = true)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // Stable provider-issued subject id (Google/Apple "sub"). Null for LOCAL.
    @Column(name = "provider_subject", length = 255)
    private String providerSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", nullable = false)
    @Builder.Default
    private SubscriptionTier subscriptionTier = SubscriptionTier.FREE;

    @Column(name = "contribution_opt_in", nullable = false)
    @Builder.Default
    private boolean contributionOptIn = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * When true, this account is excluded from ALL admin metrics by default
     * (same as admins / @economizaai.app test accounts). Used to hide store-review
     * and robo test accounts (Google Firebase Test Lab, app reviewers) without
     * deleting them. Toggled via the admin API.
     */
    @Column(name = "excluded_from_metrics", nullable = false)
    @Builder.Default
    private boolean excludedFromMetrics = false;

    // S1948 is resolved by making BaseEntity (and thus Household) Serializable,
    // NOT by Java `transient`: in a JPA entity, the `transient` keyword is read as
    // @Transient (non-persistent), which drops the household_id mapping and breaks
    // every JPQL path through u.household (UnknownPathException at startup).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(name = "accepted_terms_version", nullable = false, length = 20)
    private String acceptedTermsVersion;

    @Column(name = "accepted_privacy_version", nullable = false, length = 20)
    private String acceptedPrivacyVersion;

    @Column(name = "accepted_legal_at", nullable = false)
    private LocalDateTime acceptedLegalAt;

    @Column(name = "home_latitude", precision = 10, scale = 7)
    private BigDecimal homeLatitude;

    @Column(name = "home_longitude", precision = 10, scale = 7)
    private BigDecimal homeLongitude;

    @Column(name = "home_set_at")
    private LocalDateTime homeSetAt;

    @Column(name = "push_device_token", length = 500)
    private String pushDeviceToken;

    @Column(name = "push_token_updated_at")
    private LocalDateTime pushTokenUpdatedAt;

    // Daily deals digest (Phase C): how often, an optional send-hour override
    // (0-23, wall-clock America/Sao_Paulo), and the 1/day cap marker.
    @Enumerated(EnumType.STRING)
    @Column(name = "digest_frequency", nullable = false, length = 10)
    @Builder.Default
    private DigestFrequency digestFrequency = DigestFrequency.DAILY;

    @Column(name = "digest_send_hour")
    private Integer digestSendHour;

    @Column(name = "last_digest_sent_at")
    private OffsetDateTime lastDigestSentAt;

    @Column(name = "profile_picture_key", length = 255)
    private String profilePictureKey;

    @Column(name = "profile_picture_content_type", length = 50)
    private String profilePictureContentType;

    @Column(name = "profile_picture_uploaded_at")
    private LocalDateTime profilePictureUploadedAt;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    // E.164 phone number (e.g. +5551999999999). Nullable until the user sets it.
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    // Platform used when the account was first created (immutable once set).
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_platform", length = 10)
    private Platform registrationPlatform;

    // --- Marketing attribution (immutable, captured once at signup) ---
    // Raw UTM params + platform click id + referrer the FE read off the landing
    // URL. acquisitionChannel is the derived bucket the dashboard groups by.
    @Column(name = "utm_source", length = 120)
    private String utmSource;

    @Column(name = "utm_medium", length = 120)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 200)
    private String utmCampaign;

    @Column(name = "utm_content", length = 200)
    private String utmContent;

    @Column(name = "utm_term", length = 200)
    private String utmTerm;

    @Column(name = "attribution_click_id", length = 500)
    private String attributionClickId;

    @Column(name = "attribution_referrer", length = 500)
    private String attributionReferrer;

    @Column(name = "attribution_landing_path", length = 500)
    private String attributionLandingPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_channel", length = 40)
    private AcquisitionChannel acquisitionChannel;

    // Platform used on the most recent login (overwritten every login).
    @Enumerated(EnumType.STRING)
    @Column(name = "last_platform", length = 10)
    private Platform lastPlatform;

    // Last time the user logged in from each platform.
    @Column(name = "last_web_login_at")
    private OffsetDateTime lastWebLoginAt;

    @Column(name = "last_android_login_at")
    private OffsetDateTime lastAndroidLoginAt;

    @Column(name = "last_ios_login_at")
    private OffsetDateTime lastIosLoginAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return active;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
