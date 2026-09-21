package com.relyon.economizai.service.analytics;

import com.relyon.economizai.dto.response.AcquisitionReportResponse;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.AdSpendSummary;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.CampaignLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.CampaignSpendLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.ChannelLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.ChannelRevenueLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.DailySignupLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.Funnel;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.PlatformLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.RetentionLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.RevenueSummary;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.VisitCampaignLine;
import com.relyon.economizai.dto.response.AcquisitionReportResponse.VisitSummary;
import com.relyon.economizai.dto.response.SubscriptionReportResponse;
import com.relyon.economizai.config.MonetizationProperties;
import com.relyon.economizai.model.MetaCampaign;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.repository.MetaAdSpendRepository;
import com.relyon.economizai.repository.MetaCampaignRepository;
import com.relyon.economizai.repository.RevenueEventRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.repository.VisitRepository;
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
    private final RevenueEventRepository revenueEventRepository;
    private final VisitRepository visitRepository;
    private final MonetizationProperties monetizationProperties;

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
        var revenue = buildRevenue(since, byChannel, adSpend, funnel, includeInternal);
        var visits = buildVisits(since, byCampaign, funnel.signups());
        var retention = buildRetention(since, includeInternal);

        log.info("analytics.acquisition days={} includeInternal={} signups={} paidSignups={} spend={} realizedRevenue={} visits={}",
                windowDays, includeInternal, funnel.signups(), adSpend.paidSignups(), adSpend.totalSpend(),
                revenue.realizedRevenue(), visits.totalVisits());
        return new AcquisitionReportResponse(windowDays, from, to, funnel, timeline,
                byChannel, byPlatform, byCampaign, adSpend, revenue, visits, retention);
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
            var channel = channelName(row[6]);
            var matchedSpend = campaign == null ? null : spendByCampaignName.get(campaign.toLowerCase());
            var costPerSignup = matchedSpend != null && signups > 0
                    ? matchedSpend.divide(BigDecimal.valueOf(signups), 2, RoundingMode.HALF_UP)
                    : null;
            lines.add(new CampaignLine(channel, source, medium, campaign, signups,
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

    /**
     * Money view. Realized revenue is real provider money in the window; everything
     * else is modeled from the configured PRO price until real payments accumulate.
     * ROAS/LTV:CAC let the owner judge whether a channel's spend is profitable.
     */
    private RevenueSummary buildRevenue(LocalDateTime since, List<ChannelLine> byChannel,
                                        AdSpendSummary adSpend, Funnel funnel, boolean includeInternal) {
        var monthlyPrice = scale(monetizationProperties.getMonthlyPrice());
        var ltvPerProUser = monetizationProperties.ltvPerProUser();
        var realizedRevenue = scale(revenueEventRepository.totalRealizedSince(since, includeInternal));

        var payingCustomers = subscriptionRepository.countPaying(SubscriptionStatus.ACTIVE, includeInternal);
        var avgTicket = payingCustomers > 0
                ? realizedRevenue.divide(BigDecimal.valueOf(payingCustomers), 2, RoundingMode.HALF_UP)
                : scale(BigDecimal.ZERO);

        var realizedByChannel = new LinkedHashMap<String, BigDecimal>();
        for (var row : revenueEventRepository.realizedRevenueByChannelSince(since, includeInternal)) {
            realizedByChannel.put(channelName(row[0]), scale((BigDecimal) row[1]));
        }

        var channelLines = new ArrayList<ChannelRevenueLine>();
        var proSignupsWindow = 0L;
        for (var line : byChannel) {
            proSignupsWindow += line.proTier();
            var projectedLtv = ltvPerProUser.multiply(BigDecimal.valueOf(line.proTier())).setScale(2, RoundingMode.HALF_UP);
            var realized = realizedByChannel.getOrDefault(line.channel(), scale(BigDecimal.ZERO));
            channelLines.add(new ChannelRevenueLine(line.channel(), line.proTier(), realized, projectedLtv));
        }

        var proAllTime = userRepository.countProTier(includeInternal);
        var mrrProxy = monthlyPrice.multiply(BigDecimal.valueOf(proAllTime)).setScale(2, RoundingMode.HALF_UP);
        var projectedLtv = ltvPerProUser.multiply(BigDecimal.valueOf(proSignupsWindow)).setScale(2, RoundingMode.HALF_UP);
        var roas = ratio(realizedRevenue, adSpend.totalSpend());
        var projectedRoas = ratio(projectedLtv, adSpend.totalSpend());
        var ltvToCac = adSpend.costPerSignup() == null ? null : ratio(ltvPerProUser, adSpend.costPerSignup());
        var realized = realizedRevenue.signum() > 0;
        var note = realized ? null
                : "No real revenue yet — LTV/ROAS are modeled from the configured PRO price (edit PRO_MONTHLY_PRICE / PRO_ASSUMED_LIFETIME_MONTHS).";
        return new RevenueSummary(CURRENCY, realized, monetizationProperties.getAssumedLifetimeMonths(),
                monthlyPrice, realizedRevenue, payingCustomers, avgTicket, mrrProxy, ltvPerProUser, projectedLtv,
                roas, projectedRoas, ltvToCac, channelLines, note);
    }

    /** First-party click→signup: anonymous visits (unique by anon id) vs signups, per campaign. */
    private VisitSummary buildVisits(LocalDateTime since, List<CampaignLine> byCampaign, long totalSignups) {
        var totalVisits = visitRepository.countSince(since);
        var uniqueVisitors = visitRepository.countUniqueVisitorsSince(since);

        var signupsByCampaign = new LinkedHashMap<String, Long>();
        for (var line : byCampaign) {
            if (line.campaign() != null) {
                signupsByCampaign.merge(line.campaign().toLowerCase(), line.signups(), Long::sum);
            }
        }
        var lines = new ArrayList<VisitCampaignLine>();
        for (var row : visitRepository.campaignTotalsSince(since)) {
            var campaign = (String) row[0];
            var uniqueVisits = toLong(row[4]);
            var signups = campaign == null ? 0L : signupsByCampaign.getOrDefault(campaign.toLowerCase(), 0L);
            lines.add(new VisitCampaignLine(channelName(row[3]), (String) row[1], (String) row[2], campaign,
                    uniqueVisits, signups, uniqueVisits > 0 ? rate(signups, uniqueVisits) : 0d));
        }
        lines.sort((left, right) -> Long.compare(right.uniqueVisitors(), left.uniqueVisitors()));
        var overallRate = uniqueVisitors > 0 ? rate(totalSignups, uniqueVisitors) : 0d;
        var note = totalVisits == 0
                ? "No visits recorded yet — the web beacon isn't live or hasn't received traffic in this window."
                : null;
        return new VisitSummary(totalVisits, uniqueVisitors, totalSignups, overallRate, lines, note);
    }

    /**
     * Per-channel cohort quality. "Retention" here is activation (first receipt)
     * within 7/30 days of signup — the earliest real engagement signal we have.
     * Denominators exclude signups too fresh to have had the full window.
     */
    private List<RetentionLine> buildRetention(LocalDateTime since, boolean includeInternal) {
        var now = LocalDateTime.now();
        var cutoff7 = now.minusDays(7);
        var cutoff30 = now.minusDays(30);
        // Per channel: [cohort, activated, cohort7, retained7, cohort30, retained30].
        var byChannel = new LinkedHashMap<String, long[]>();
        for (var row : userRepository.signupActivationSince(since, includeInternal)) {
            var channel = channelName(row[0]);
            var createdAt = (LocalDateTime) row[1];
            var firstReceiptAt = (LocalDateTime) row[2];
            var accumulator = byChannel.computeIfAbsent(channel, ignored -> new long[6]);
            accumulator[0]++;
            if (firstReceiptAt != null) {
                accumulator[1]++;
            }
            if (!createdAt.isAfter(cutoff7)) {
                accumulator[2]++;
                if (firstReceiptAt != null && !firstReceiptAt.isAfter(createdAt.plusDays(7))) {
                    accumulator[3]++;
                }
            }
            if (!createdAt.isAfter(cutoff30)) {
                accumulator[4]++;
                if (firstReceiptAt != null && !firstReceiptAt.isAfter(createdAt.plusDays(30))) {
                    accumulator[5]++;
                }
            }
        }
        var lines = new ArrayList<RetentionLine>();
        byChannel.forEach((channel, accumulator) -> lines.add(new RetentionLine(channel,
                accumulator[0], accumulator[1], rate(accumulator[1], accumulator[0]),
                accumulator[2], accumulator[3], rate(accumulator[3], accumulator[2]),
                accumulator[4], accumulator[5], rate(accumulator[5], accumulator[4]))));
        lines.sort((left, right) -> Long.compare(right.cohort(), left.cohort()));
        return lines;
    }

    /** a ÷ b, scaled to 2 decimals; null when b is null or zero (avoids a misleading 0/∞). */
    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
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
