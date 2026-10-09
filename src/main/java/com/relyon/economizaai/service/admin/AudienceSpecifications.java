package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Translates a {@link NotificationAudience}'s nullable filters into a JPA
 * {@link Specification} over users. Always scoped to active accounts; each
 * non-null filter adds one AND predicate. Resolved at send time, so audiences
 * track the live user base.
 */
public final class AudienceSpecifications {

    private AudienceSpecifications() {
    }

    public static Specification<User> toSpecification(NotificationAudience audience) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(builder.isTrue(root.get("active")));
            if (audience.getRole() != null) {
                predicates.add(builder.equal(root.get("role"), audience.getRole()));
            }
            if (audience.getSubscriptionTier() != null) {
                predicates.add(builder.equal(root.get("subscriptionTier"), audience.getSubscriptionTier()));
            }
            if (audience.getLocale() != null && !audience.getLocale().isBlank()) {
                predicates.add(builder.equal(root.get("locale"), audience.getLocale()));
            }
            if (audience.getHasPushToken() != null) {
                var token = root.<String>get("pushDeviceToken");
                predicates.add(audience.getHasPushToken()
                        ? builder.and(builder.isNotNull(token), builder.notEqual(token, ""))
                        : builder.or(builder.isNull(token), builder.equal(token, "")));
            }
            if (audience.getRegisteredWithinDays() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"),
                        LocalDateTime.now().minusDays(audience.getRegisteredWithinDays())));
            }
            if (audience.getActiveWithinDays() != null) {
                var receiptQuery = query.subquery(Long.class);
                var receipt = receiptQuery.from(Receipt.class);
                receiptQuery.select(builder.literal(1L))
                        .where(builder.equal(receipt.get("user"), root),
                                builder.greaterThanOrEqualTo(receipt.get("createdAt"),
                                        LocalDateTime.now().minusDays(audience.getActiveWithinDays())));
                predicates.add(builder.exists(receiptQuery));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
