package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.RelevanceThreshold;
import com.relyon.economizaai.service.priceindex.DealsService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily deals digest (Phase C): once an hour, for every user whose stored send
 * hour has already been reached today (America/Sao_Paulo) and who hasn't been
 * sent a digest today, compute their relevant deals and — only when at least one
 * is <em>newsworthy</em> (new, meaningfully improved, or lapsed past the
 * collaborative lookback) — send ONE rollup push pointing at the deals screen.
 *
 * <p>Due users are selected in SQL, in pages ({@code UserRepository.findDigestDuePage}):
 * "hour reached" instead of "hour equal", so a late or long run still delivers.
 * A user with nothing newsworthy keeps the day's slot and is looked at again on
 * the next hourly run. The slot ({@code User.lastDigestSentAt}) is claimed
 * atomically right before the send, so overlapping runs never send twice.
 *
 * <p>A single user's failure is logged and skipped so it never aborts the run.
 * Each surfaced newsworthy deal upserts its {@link DealSurfaceState} dedup row
 * and records a {@code SENT} telemetry event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealsDigestScheduler {

    private static final int PAGE_SIZE = 200;
    /** Sorts before every real id — the starting point of the keyset pagination. */
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);
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
    private final NotificationSettingsService settingsService;
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
        if (!settingsService.isEnabled(NotificationJob.DEALS_DIGEST)) return;
        scheduleService.assignMissingSendHours();
        var now = BrazilClock.now();
        var weeklyDay = WEEKLY_DAY.equals(now.getDayOfWeek());
        var startOfToday = startOfDay(now.toOffsetDateTime());

        var sent = 0;
        var processed = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var dueUsers = userRepository.findDigestDuePage(
                    now.getHour(), weeklyDay, startOfToday, afterId, PageRequest.of(0, PAGE_SIZE));
            if (dueUsers.isEmpty()) break;
            for (var user : dueUsers) {
                processed++;
                if (processUserIsolated(user, now.toOffsetDateTime())) sent++;
            }
            afterId = dueUsers.get(dueUsers.size() - 1).getId();
        }
        log.info("digest.run hour={} due={} sent={}", now.getHour(), processed, sent);
    }

    /** Isolate per-user failures so one bad user can't abort the batch. */
    private boolean processUserIsolated(User user, OffsetDateTime now) {
        try {
            return effectiveSelf().processUser(user, now);
        } catch (RuntimeException ex) {
            log.warn("digest.user_failed user={} reason={}",
                    LogMasker.email(user.getEmail()), ex.getMessage());
            return false;
        }
    }

    /** Midnight (Sao Paulo) of the day {@code now} falls on — the 1/day cap boundary. */
    private OffsetDateTime startOfDay(OffsetDateTime now) {
        return now.atZoneSameInstant(BrazilClock.ZONE).toLocalDate()
                .atStartOfDay(BrazilClock.ZONE).toOffsetDateTime();
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
     * run untransacted; the post-send state (per-deal dedup upserts and SENT telemetry rows) is then
     * persisted in ONE short transaction (same split as
     * {@code MarketLocationService.geocodeOne}/{@code persistGeocodeResult}).
     *
     * <p>Nothing newsworthy → nothing is sent and the day's slot is NOT taken (an empty digest
     * must not use up the once-a-day send). Otherwise the slot is claimed first and the send
     * follows only for the run that won the claim.
     */
    public boolean processUser(User user, OffsetDateTime now) {
        var deals = dealsService.findDeals(user, true, null, DEAL_LIMIT);
        var newsworthy = deals.stream().filter(deal -> isNewsworthy(user, deal, now)).toList();
        if (newsworthy.isEmpty()) {
            log.info("digest.skip user={} reason=nothing_newsworthy deals={}",
                    LogMasker.email(user.getEmail()), deals.size());
            return false;
        }

        if (userRepository.claimDigestSlot(user.getId(), now, startOfDay(now)) == 0) {
            log.info("digest.skip user={} reason=already_sent_today", LogMasker.email(user.getEmail()));
            return false;
        }
        user.setLastDigestSentAt(now);

        var best = newsworthy.get(0); // DealsService already ranks best-first
        var notification = notificationService.notify(buildPayload(user, best, newsworthy.size()));
        var notificationId = notification != null ? notification.getId() : null;

        effectiveSelf().persistDigest(user, newsworthy, notificationId, now);
        scheduleService.updateSendHourFromShoppingHabits(user);
        log.info("digest.sent user={} best={} newsworthy={}",
                LogMasker.email(user.getEmail()), best.productName(), newsworthy.size());
        return true;
    }

    /**
     * Persist everything the send produced in one short transaction, AFTER the network dispatch:
     * each newsworthy deal's {@link DealSurfaceState} dedup upsert and its {@code SENT} telemetry
     * event. (The 1/day cap was already taken by the claim, with a targeted update.) REQUIRES_NEW
     * keeps one user's persistence isolated from the batch so a single failure is caught in {@link #run}.
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
        if (previousUnitPrice == null) return false;
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
        // Nome do produto em destaque — o app abre os "Melhores Mercados" dele no tap (qual mercado
        // tem mais barato), e a tela precisa do nome pro cabeçalho.
        extras.put("bestProductName", productLabel);
        extras.put("bestMarketCnpj", best.marketCnpj());
        extras.put("bestDiscountFraction", best.discountFraction());
        return new NotificationPayload(user, NotificationType.DEALS_DIGEST, title, body, extras);
    }
}
