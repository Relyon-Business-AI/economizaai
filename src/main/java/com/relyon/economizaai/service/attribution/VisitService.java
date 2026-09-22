package com.relyon.economizaai.service.attribution;

import com.relyon.economizaai.dto.request.AttributionInfo;
import com.relyon.economizaai.dto.request.VisitBeaconRequest;
import com.relyon.economizaai.model.Visit;
import com.relyon.economizaai.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persists anonymous top-of-funnel visits. Derives the same
 * {@link com.relyon.economizaai.model.enums.AcquisitionChannel} as signup (via
 * {@link AttributionResolver}) so a visit and the signup it later produces roll
 * up under the same channel/campaign. The client IP is stored only as a one-way
 * hash — enough to spot bot floods, never the raw address (LGPD).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitRepository visitRepository;
    private final AttributionResolver attributionResolver;

    @Transactional
    public void record(VisitBeaconRequest request, String clientIp, String userAgent) {
        if (request == null || request.anonId() == null || request.anonId().isBlank()) {
            log.debug("visit.ignored reason=missing_anon_id");
            return;
        }
        var attribution = request.attribution();
        var channel = attributionResolver.resolveChannel(attribution);
        var visit = Visit.builder()
                .anonId(truncate(request.anonId(), 64))
                .platform(truncate(request.platform(), 20))
                .acquisitionChannel(channel)
                .ipHash(hash(clientIp))
                .userAgent(truncate(userAgent, 400))
                .utmSource(truncate(value(attribution, AttributionInfo::utmSource), 120))
                .utmMedium(truncate(value(attribution, AttributionInfo::utmMedium), 120))
                .utmCampaign(truncate(value(attribution, AttributionInfo::utmCampaign), 200))
                .utmContent(truncate(value(attribution, AttributionInfo::utmContent), 200))
                .utmTerm(truncate(value(attribution, AttributionInfo::utmTerm), 200))
                .clickId(truncate(value(attribution, AttributionInfo::clickId), 500))
                .referrer(truncate(value(attribution, AttributionInfo::referrer), 500))
                .landingPath(truncate(value(attribution, AttributionInfo::landingPath), 500))
                .build();
        visitRepository.save(visit);
        log.info("visit.recorded channel={} campaign={}", channel, visit.getUtmCampaign());
    }

    private static String value(AttributionInfo attribution, java.util.function.Function<AttributionInfo, String> getter) {
        return attribution == null ? null : getter.apply(attribution);
    }

    private static String hash(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(clientIp.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
