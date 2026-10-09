package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A reusable user segment campaigns are sent to. Every filter field is nullable
 * ("don't filter on this"); the non-null ones combine with AND. Resolution to
 * concrete users happens at send time via {@code AudienceSpecifications}, so an
 * audience always reflects the CURRENT user base, never a frozen list.
 *
 * <p>Built-in audiences (seeded by migration, e.g. "Admins") are locked:
 * not editable, not deletable — they're the safe test targets.
 */
@Entity
@Table(name = "notification_audiences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class NotificationAudience extends BaseEntity {

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "built_in", nullable = false)
    @lombok.Builder.Default
    private boolean builtIn = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", length = 20)
    private SubscriptionTier subscriptionTier;

    @Column(length = 5)
    private String locale;

    /** true = only users with a registered push token; false = only without; null = both. */
    @Column(name = "has_push_token")
    private Boolean hasPushToken;

    /** Only users who registered in the last N days. */
    @Column(name = "registered_within_days")
    private Integer registeredWithinDays;

    /** Only users with at least one receipt scanned in the last N days. */
    @Column(name = "active_within_days")
    private Integer activeWithinDays;
}
