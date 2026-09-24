package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.RelevanceThreshold;
import com.relyon.economizaai.service.priceindex.DealsService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily deals digest (Phase C): once an hour, for every user whose effective
 * send hour is the current America/Sao_Paulo hour and who hasn't been sent a
 * digest today, compute their relevant deals and — only when at least one is
 * <em>newsworthy</em> (new, meaningfully improved, or lapsed past the
 * collaborative lookback) — send ONE rollup push pointing at the deals screen.
 *
 * <p>One transaction per user (not one giant batch tx): a single user's failure
 * is logged and skipped so it never aborts the run. Each surfaced newsworthy
 * deal upserts its {@link DealSurfaceState} dedup row and records a {@code SENT}
 * telemetry event; the 1/day cap is marked via {@code User.lastDigestSentAt}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealsDigestScheduler {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    /** WEEKLY digests only go out on this weekday (see DEV_NOTES). */
    private static final DayOfWeek WEEKLY_DAY = DayOfWeek.THURSDAY;
    /** Minimum discount-fraction improvement (5 p.p.) that re-qualifies a standing deal. */
    private static final BigDecimal IMPROVEMENT_STEP = new BigDecimal("0.05");
    /** How many deals we ask DealsService for per user. */
    private static final int DEAL_LIMIT = 50;

    private final UserRepository userRepository;
    private final LocalizedMessageService messageService;
    private final DealsService dealsService;
    private final DealSurfaceStateRepository surfaceStateRepository;
    private final NotificationService notificationService;
    private final NotificationEventService eventService;
    private final CollaborativeProperties properties;
    private final DigestScheduleService scheduleService;

    // Self-reference so the per-user @Transactional(REQUIRES_NEW) boundary is
    // honored through the Spring proxy (self-invocation would bypass it).
    @Lazy
    @Autowired
    private DealsDigestScheduler self;

    @Scheduled(cron = "${economizaai.notifications.deals-digest.cron:0 0 * * * *}",
            zone = "${economizaai.notifications.deals-digest.zone:America/Sao_Paulo}")
    public void run() {
        var now = ZonedDateTime.now(ZONE);
        var currentHour = now.getHour();
        var candidates = userRepository.findDigestCandidates(DigestFrequency.OFF);

        var sent = 0;
        var processed = 0;
        for (var user : candidates) {
            if (!isDue(user, now, currentHour)) continue;
            processed++;
            try {
                if (effectiveSelf().processUser(user, now.toOffsetDateTime())) sent++;
            } catch (RuntimeException ex) {
                // Isolate per-user failures so one bad user can't abort the batch.
                log.warn("digest.user_failed user={} reason={}",
                        LogMasker.email(user.getEmail()), ex.getMessage());
            }
        }
        log.info("digest.run hour={} candidates={} sent={}", currentHour, processed, sent);
    }

    private boolean isDue(User user, ZonedDateTime now, int currentHour) {
        if (user.getDigestFrequency() == DigestFrequency.WEEKLY && !WEEKLY_DAY.equals(now.getDayOfWeek())) {
            return false;
        }
        if (alreadySentToday(user, now)) return false;
        return scheduleService.effectiveSendHour(user) == currentHour;
    }

    /** 1/day hard cap: a digest is allowed at most once per calendar day (Sao Paulo). */
    private boolean alreadySentToday(User user, ZonedDateTime now) {
        var last = user.getLastDigestSentAt();
        if (last == null) return false;
        return last.atZoneSameInstant(ZONE).toLocalDate().isEqual(now.toLocalDate());
    }

    /** The transactional proxy when wired by Spring; falls back to {@code this} in unit tests. */
    private DealsDigestScheduler effectiveSelf() {
        return self != null ? self : this;
    }

    /**
     * One send per user; returns true when a digest was actually sent.
     *
     * <p>Deliberately NOT {@code @Transactional}: {@code notificationService.notify(...)} dispatches
     * over the network (Expo push / SMTP) and holding a transaction across it would pin a Hikari
     * connection for the whole round-trip, starving the pool under load. The deal reads and the send
     * run untransacted; the post-send state (per-deal dedup upserts, SENT telemetry rows, and the
     * 1/day {@code lastDigestSentAt} cap) is then persisted in ONE short transaction — the "already
     * sent today" guard still gates entry via {@link #isDue}, and the whole persist block is atomic
     * (same split as {@code MarketLocationService.geocodeOne}/{@code persistGeocodeResult}).
     */
    public boolean processUser(User user, OffsetDateTime now) {
        var deals = dealsService.findDeals(user, false, null, DEAL_LIMIT);
        var newsworthy = deals.stream().filter(deal -> isNewsworthy(user, deal, now)).toList();
        if (newsworthy.isEmpty()) {
            log.info("digest.skip user={} reason=nothing_newsworthy deals={}",
                    LogMasker.email(user.getEmail()), deals.size());
            return false;
        }

        var best = newsworthy.get(0); // DealsService already ranks best-first
        var notification = notificationService.notify(buildPayload(user, best, newsworthy.size()));
        var notificationId = notification != null ? notification.getId() : null;

        effectiveSelf().persistDigest(user, newsworthy, notificationId, now);
        log.info("digest.sent user={} best={} newsworthy={}",
                LogMasker.email(user.getEmail()), best.productName(), newsworthy.size());
        return true;
    }

    /**
     * Persist everything the send produced in one short transaction, AFTER the network dispatch:
     * each newsworthy deal's {@link DealSurfaceState} dedup upsert and its {@code SENT} telemetry
     * event, then the 1/day cap on {@code User.lastDigestSentAt}. REQUIRES_NEW keeps one user's
     * persistence isolated from the batch so a single failure is caught in {@link #run}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistDigest(User user, List<DealResponse> newsworthy, UUID notificationId, OffsetDateTime now) {
        for (var deal : newsworthy) {
            upsertState(user, deal, now);
            eventService.record(user, NotificationEventType.SENT, RecordContext.builder()
                    .notificationId(notificationId)
                    .productId(deal.productId())
                    .marketCnpj(deal.marketCnpj())
                    .channel("PUSH")
                    .metadata(Map.of("discountFraction", deal.discountFraction()))
                    .build());
        }
        user.setLastDigestSentAt(now);
        userRepository.save(user);
    }

    /**
     * Newsworthy when: (a) no prior state row (brand-new deal), OR (b) the discount
     * improved by >= {@link #IMPROVEMENT_STEP} (5 p.p.) or crossed a higher
     * relevance-threshold step vs what we last surfaced, OR (c) the last surface is
     * older than the collaborative lookback window (a lapsed deal worth re-airing).
     */
    private boolean isNewsworthy(User user, DealResponse deal, OffsetDateTime now) {
        var state = surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(
                user.getId(), deal.productId(), deal.marketCnpj());
        if (state.isEmpty()) return true;

        var stored = state.get();
        var lookbackCutoff = now.minusDays(properties.getCollaborative().getLookbackDays());
        if (stored.getLastSurfacedAt() == null || stored.getLastSurfacedAt().isBefore(lookbackCutoff)) {
            return true;
        }

        var previousFraction = stored.getLastDiscountFraction();
        if (previousFraction == null) return true;
        var current = deal.discountFraction();
        if (current.subtract(previousFraction).compareTo(IMPROVEMENT_STEP) >= 0) return true;
        return crossedHigherThresholdStep(stored.getLastUnitPrice(), deal);
    }

    /** True when the deal now clears a stricter relevance bar than the price we last surfaced did. */
    private boolean crossedHigherThresholdStep(BigDecimal previousUnitPrice, DealResponse deal) {
        if (previousUnitPrice == null || deal.lastPaidPrice() == null) return false;
        var previousRequired = RelevanceThreshold.requiredDropFraction(previousUnitPrice);
        var currentRequired = RelevanceThreshold.requiredDropFraction(deal.currentPrice());
        return currentRequired > previousRequired;
    }

    private void upsertState(User user, DealResponse deal, OffsetDateTime now) {
        var existing = surfaceStateRepository.findByUserIdAndProductIdAndMarketCnpj(
                user.getId(), deal.productId(), deal.marketCnpj());
        var state = existing.orElseGet(() -> DealSurfaceState.builder()
                .userId(user.getId())
                .productId(deal.productId())
                .marketCnpj(deal.marketCnpj())
                .build());
        state.setLastDiscountFraction(deal.discountFraction());
        state.setLastUnitPrice(deal.currentPrice());
        state.setLastSurfacedAt(now);
        surfaceStateRepository.save(state);
    }

    private NotificationPayload buildPayload(User user, DealResponse best, int newsworthyCount) {
        var discountPct = best.discountFraction()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        var others = newsworthyCount - 1;
        var locale = LocalizedMessageService.toLocale(user.getLocale());
        var dealWord = messageService.translate(
                others == 1 ? "notification.deals.word.one" : "notification.deals.word.many", locale);
        var title = messageService.translate("notification.deals.title", locale);
        var productLabel = best.friendlyDescription() != null ? best.friendlyDescription() : best.productName();
        var body = others > 0
                ? messageService.translate("notification.deals.body.multi", locale,
                        productLabel, String.valueOf(discountPct), String.valueOf(others), dealWord)
                : messageService.translate("notification.deals.body.single", locale,
                        productLabel, String.valueOf(discountPct));

        var extras = new HashMap<String, Object>();
        extras.put("deeplink", "economizai://deals");
        extras.put("screen", "deals");
        extras.put("newsworthyCount", newsworthyCount);
        extras.put("bestProductId", best.productId().toString());
        extras.put("bestMarketCnpj", best.marketCnpj());
        extras.put("bestDiscountFraction", best.discountFraction());
        return new NotificationPayload(user, NotificationType.DEALS_DIGEST, title, body, extras);
    }
}
