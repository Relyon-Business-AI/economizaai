package com.relyon.economizai.service.attribution;

import com.relyon.economizai.dto.request.AttributionInfo;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Stamps a newly-created {@link User} with the marketing attribution the FE
 * forwarded at signup and derives its {@link AcquisitionChannel}. Called once,
 * at registration only — attribution is immutable afterwards.
 */
@Slf4j
@Component
public class AttributionResolver {

    private static final Set<String> PAID_MEDIUMS =
            Set.of("cpc", "ppc", "paid", "paidsocial", "paid_social", "paid-social", "cpm", "display", "social_paid");
    private static final Set<String> META_SOURCES =
            Set.of("instagram", "ig", "facebook", "fb", "meta");
    private static final Set<String> GOOGLE_SOURCES =
            Set.of("google", "youtube", "gads", "adwords");

    public void applyTo(User user, AttributionInfo attribution) {
        if (attribution == null || attribution.isEmpty()) {
            user.setAcquisitionChannel(AcquisitionChannel.UNKNOWN);
            return;
        }
        user.setUtmSource(truncate(attribution.utmSource(), 120));
        user.setUtmMedium(truncate(attribution.utmMedium(), 120));
        user.setUtmCampaign(truncate(attribution.utmCampaign(), 200));
        user.setUtmContent(truncate(attribution.utmContent(), 200));
        user.setUtmTerm(truncate(attribution.utmTerm(), 200));
        user.setAttributionClickId(truncate(attribution.clickId(), 500));
        user.setAttributionReferrer(truncate(attribution.referrer(), 500));
        user.setAttributionLandingPath(truncate(attribution.landingPath(), 500));

        var channel = deriveChannel(attribution);
        user.setAcquisitionChannel(channel);
        log.info("attribution.captured channel={} source={} campaign={}",
                channel, attribution.utmSource(), attribution.utmCampaign());
    }

    private AcquisitionChannel deriveChannel(AttributionInfo attribution) {
        var source = normalize(attribution.utmSource());
        var medium = normalize(attribution.utmMedium());
        var paid = PAID_MEDIUMS.contains(medium)
                || (attribution.clickId() != null && !attribution.clickId().isBlank());

        if (paid) {
            if (META_SOURCES.contains(source)) {
                return AcquisitionChannel.INSTAGRAM_PAID;
            }
            if (GOOGLE_SOURCES.contains(source)) {
                return AcquisitionChannel.GOOGLE_PAID;
            }
            return AcquisitionChannel.PAID_OTHER;
        }
        if (notBlank(attribution.utmSource()) || notBlank(attribution.utmCampaign())) {
            return AcquisitionChannel.ORGANIC;
        }
        if (notBlank(attribution.referrer())) {
            return AcquisitionChannel.REFERRAL;
        }
        return AcquisitionChannel.DIRECT;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
