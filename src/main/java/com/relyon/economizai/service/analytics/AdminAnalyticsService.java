package com.relyon.economizai.service.analytics;

import com.relyon.economizai.dto.response.AcquisitionReportResponse;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.AdSpendSummary;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.CampaignLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.CampaignSpendLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.ChannelLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.DailySignupLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.Funnel;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.PlatformLine;
import com.relyon.economizai.dto.response.SubscriptionReportResponse;
import com.relyon.economizai.model.MetaCampaign;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.repository.MetaAdSpendRepository;
import com.relyon.economizai.repository.MetaCampaignRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.analytics.meta.MetaAdsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Read-only acquisition & subscription analytics for the admin dashboard. Every
 * number is derived from the users / subscriptions / meta_ad_spend tables — no
 * writes. Windows are inclusive day ranges anchored on today (server local date).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final String CURRENCY = "BRL";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MetaAdSpendRepository metaAdSpendRepository;
    private final MetaCampaignRepository metaCampaignRepository;
    private final MetaAdsProperties metaAdsProperties;

    @Transactional(readOnly = true)
    public AcquisitionReportResponse acquisition(int days, boolean includeInternal) {
        var windowDays = Math.max(1, days);
        var to = LocalDate.now();
        var from = to.minusDays(windowDays - 1L);
        var since = from.atStartOfDay();

        var funnel = buildFunnel(since, includeInternal);
        var timeline = buildTimeline(since, from, to, includeInternal);
        var byChannel = buildChannelLines(since, includeInternal);
        var byPlatform = buildPlatformLines(since, includeInternal);
        var adSpend = buildAdSpend(from, to, byChannel, includeInternal);
        var byCampaign = buildCampaignLines(since, adSpend, includeInternal);

        log.info("analytics.acquisition days={} includeInternal={} signups={} paidSignups={} spend={}",
                windowDays, includeInternal, funnel.signups(), adSpend.paidSignups(), adSpend.totalSpend());
        return new AcquisitionReportResponse(windowDays, from, to, funnel, timeline,
                byChannel, byPlatform, byCampaign, adSpend);
    }

    @Transactional(readOnly = true)
    public SubscriptionReportResponse subscriptions(boolean includeInternal) {
        var byTier = new LinkedHashMap<String, Long>();
        var total = 0L;
        for (var row : userRepository.tierDistribution(includeInternal)) {
            var tier = String.valueOf(row[0]);
            var count = ((Number) row[1]).longValue();
            byTier.put(tier, count);
            total += count;
        }
        var paying = subscriptionRepository.countPaying(SubscriptionStatus.ACTIVE, includeInternal);
        var promo = subscriptionRepository.countPromoGranted(SubscriptionStatus.ACTIVE, includeInternal);
        var note = paying == 0 && promo > 0
                ? "No paying subscriptions yet — all PRO users are promo/admin grants (signup promo)."
                : null;
        return new SubscriptionReportResponse(total, byTier, paying, promo, note);
    }

    private Funnel buildFunnel(LocalDateTime since, boolean includeInternal) {
        var signups = userRepository.countSignupsSince(since, includeInternal);
        var verified = userRepository.countVerifiedSince(since, includeInternal);
        var activated = userRepository.countActivatedSince(since, includeInternal);
        var proTier = userRepository.countProTierSince(since, includeInternal);
        return new Funnel(signups, verified, activated, proTier,
                rate(verified, signups), rate(activated, signups), rate(proTier, signups));
    }

    private List<PlatformLine> buildPlatformLines(LocalDateTime since, boolean includeInternal) {
        var lines = new ArrayList<PlatformLine>();
        for (var row : userRepository.platformBreakdownSince(since, includeInternal)) {
            var platform = row[0] == null ? "UNKNOWN" : row[0].toString();
            lines.add(new PlatformLine(platform, ((Number) row[1]).longValue()));
        }
        lines.sort((left, right) -> Long.compare(right.signups(), left.signups()));
        return lines;
    }

    private List<DailySignupLine> buildTimeline(LocalDateTime since, LocalDate from, LocalDate to,
                                                boolean includeInternal) {
        var perDay = new TreeMap<LocalDate, Map<String, Long>>();
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
            perDay.put(day, new LinkedHashMap<>());
        }
        for (var row : userRepository.signupTimelineSince(since, includeInternal)) {
            var createdAt = (LocalDateTime) row[0];
            var day = createdAt.toLocalDate();
            var channel = channelName(row[1]);
            perDay.computeIfAbsent(day, ignored -> new LinkedHashMap<>())
                    .merge(channel, 1L, Long::sum);
        }
        var timeline = new ArrayList<DailySignupLine>();
        perDay.forEach((day, byChannel) -> {
            var total = byChannel.values().stream().mapToLong(Long::longValue).sum();
            timeline.add(new DailySignupLine(day, total, byChannel));
        });
        return timeline;
    }

    private List<ChannelLine> buildChannelLines(LocalDateTime since, boolean includeInternal) {
        var lines = new ArrayList<ChannelLine>();
        for (var row : userRepository.channelBreakdownSince(since, includeInternal)) {
            lines.add(new ChannelLine(channelName(row[0]),
                    ((Number) row[1]).longValue(),
                    toLong(row[2]),
                    toLong(row[3])));
        }
        lines.sort((left, right) -> Long.compare(right.signups(), left.signups()));
        return lines;
    }

    private List<CampaignLine> buildCampaignLines(LocalDateTime since, AdSpendSummary adSpend,
                                                  boolean includeInternal) {
        var spendByCampaignName = new LinkedHashMap<String, BigDecimal>();
        for (var line : adSpend.byCampaign()) {
            if (line.campaignName() != null) {
                spendByCampaignName.merge(line.campaignName().toLowerCase(), line.spend(), BigDecimal::add);
            }
        }
        var lines = new ArrayList<CampaignLine>();
        for (var row : userRepository.campaignBreakdownSince(since, includeInternal)) {
            var source = (String) row[0];
            var medium = (String) row[1];
            var campaign = (String) row[2];
            var signups = ((Number) row[3]).longValue();
            var matchedSpend = campaign == null ? null : spendByCampaignName.get(campaign.toLowerCase());
            var costPerSignup = matchedSpend != null && signups > 0
                    ? matchedSpend.divide(BigDecimal.valueOf(signups), 2, RoundingMode.HALF_UP)
                    : null;
            lines.add(new CampaignLine(source, medium, campaign, signups,
                    toLong(row[4]), toLong(row[5]), matchedSpend, costPerSignup));
        }
        lines.sort((left, right) -> Long.compare(right.signups(), left.signups()));
        return lines;
    }

    private AdSpendSummary buildAdSpend(LocalDate from, LocalDate to, List<ChannelLine> byChannel,
                                        boolean includeInternal) {
        var paidSignups = byChannel.stream()
                .filter(line -> AcquisitionChannel.INSTAGRAM_PAID.name().equals(line.channel()))
                .mapToLong(ChannelLine::signups)
                .sum();
        var configured = metaAdsProperties.isConfigured();
        var totalSpend = scale(metaAdSpendRepository.totalSpendBetween(from, to));

        var campaignsById = metaCampaignRepository.findAll().stream()
                .collect(Collectors.toMap(MetaCampaign::getCampaignId, campaign -> campaign, (first, second) -> first));

        var campaignLines = new ArrayList<CampaignSpendLine>();
        for (var row : metaAdSpendRepository.campaignTotalsBetween(from, to)) {
            var campaignId = (String) row[0];
            var meta = campaignsById.get(campaignId);
            var status = meta == null ? null : meta.getStatus();
            var endsAt = meta == null || meta.getEndsAt() == null ? null : meta.getEndsAt().toLocalDate();
            var ended = status != null && !"ACTIVE".equals(status);
            campaignLines.add(new CampaignSpendLine(campaignId, (String) row[1],
                    scale((BigDecimal) row[2]), toLong(row[3]), toLong(row[4]),
                    status,
                    meta == null ? null : meta.getLifetimeBudget(),
                    meta == null ? null : meta.getBudgetRemaining(),
                    endsAt, ended));
        }
        campaignLines.sort((left, right) -> right.spend().compareTo(left.spend()));

        var budgetRemaining = campaignsById.values().stream()
                .map(MetaCampaign::getBudgetRemaining)
                .filter(Objects::nonNull)
                .reduce(BigDecimal::add)
                .map(this::scaleNullable)
                .orElse(null);

        var costPerSignup = costPer(totalSpend, paidSignups);
        var costPerActivated = costPer(totalSpend, activatedPaidSignups(from, includeInternal));
        var note = resolveAdSpendNote(configured, totalSpend);
        return new AdSpendSummary(configured, CURRENCY, totalSpend, paidSignups,
                costPerSignup, costPerActivated, budgetRemaining, campaignLines, note);
    }

    private BigDecimal scaleNullable(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private long activatedPaidSignups(LocalDate from, boolean includeInternal) {
        // Cheap proxy: activations across the paid channel aren't tracked per-day, so cost-per-activated
        // reuses the window's activated total. Kept simple until per-channel activation lands.
        return userRepository.countActivatedSince(from.atStartOfDay(), includeInternal);
    }

    private String resolveAdSpendNote(boolean configured, BigDecimal totalSpend) {
        if (!configured) {
            return "Meta Ads not connected — set META_ADS_ENABLED / META_ADS_TOKEN / META_AD_ACCOUNT_ID to pull spend.";
        }
        if (totalSpend.signum() == 0) {
            return "Meta Ads connected but no spend synced yet for this window.";
        }
        return null;
    }

    private BigDecimal costPer(BigDecimal spend, long count) {
        if (spend == null || spend.signum() == 0 || count <= 0) {
            return null;
        }
        return spend.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static double rate(long part, long whole) {
        return whole <= 0 ? 0d : BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String channelName(Object channel) {
        return channel == null ? AcquisitionChannel.UNKNOWN.name() : ((AcquisitionChannel) channel).name();
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
