package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.AcquisitionReportResponse.DailySignupLine;
import com.relyon.economizaai.dto.response.AdminDashboardResponse;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.Engagement;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.PriceIndex;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.ReceiptStats;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.SignupFunnel;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.SpendStats;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.UserStats;
import com.relyon.economizaai.dto.response.AdminDashboardResponse.Window;
import com.relyon.economizaai.dto.response.AdminOverviewResponse.StateDistributionRow;
import com.relyon.economizaai.exception.InvalidDashboardPeriodException;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The admin "Visão Geral" in one call, for one date window. Unlike {@link AdminOverviewService}
 * (fixed windows, all-time totals), every period-sensitive number here follows the window the
 * admin picked: 7 days, 30 days, all time or a custom from/to. Windows are Brasília days applied
 * to {@code createdAt}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    static final String PERIOD_7_DAYS = "7d";
    static final String PERIOD_30_DAYS = "30d";
    static final String PERIOD_ALL = "all";
    static final String PERIOD_CUSTOM = "custom";

    /** Longest custom window accepted — keeps the daily timeline bounded. */
    static final long MAX_WINDOW_DAYS = 1100;
    private static final int RECENT_ROWS = 6;

    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;
    private final AdminUserService adminUserService;
    private final AdminReceiptService adminReceiptService;

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard(String period, LocalDate customFrom, LocalDate customTo, boolean includeInternal) {
        var window = resolveWindow(period, customFrom, customTo);
        var from = BrazilClock.startOfDayUtc(window.from());
        var to = BrazilClock.startOfDayUtc(window.to().plusDays(1));

        var users = new UserStats(userRepository.countUsers(includeInternal),
                userRepository.countSignupsBetween(from, to, includeInternal));
        var receipts = new ReceiptStats(receiptRepository.countReceipts(includeInternal),
                receiptRepository.countCreatedBetween(from, to, includeInternal),
                parseRate(from, to, includeInternal));
        var spend = new SpendStats(scale(receiptRepository.sumConfirmedTotal(includeInternal)),
                scale(receiptRepository.sumConfirmedTotalCreatedBetween(from, to, includeInternal)));
        var engagement = buildEngagement(window.to(), from, to, includeInternal);
        // Anonymized on purpose (no user on a price observation) — global regardless of window and toggle.
        var priceIndex = new PriceIndex(priceObservationRepository.count(),
                priceObservationAuditRepository.countDistinctContributingHouseholds());
        var funnel = buildFunnel(from, to, includeInternal);
        var timeline = buildTimeline(window, from, to, includeInternal);
        var byState = buildByState(from, to, includeInternal);

        var newestFirst = PageRequest.of(0, RECENT_ROWS, Sort.by(Sort.Direction.DESC, "createdAt"));
        var recentUsers = adminUserService.list(null, null, null, null, null, null, includeInternal, newestFirst).getContent();
        var recentReceipts = adminReceiptService.list(null, null, null, null, null, null, null, null, null, null,
                includeInternal, newestFirst).getContent();

        log.info("admin.dashboard period={} from={} to={} internal={} signups={} receipts={} spend={} active={} ufs={}",
                window.period(), window.from(), window.to(), includeInternal, users.inPeriod(), receipts.inPeriod(),
                spend.inPeriod(), engagement.activeHouseholdsInPeriod(), byState.size());
        return new AdminDashboardResponse(window, users, receipts, spend, engagement, priceIndex, funnel,
                timeline, byState, recentUsers, recentReceipts);
    }

    /** A from/to pair wins over {@code period}; with neither, the default is the last 7 days. */
    Window resolveWindow(String period, LocalDate customFrom, LocalDate customTo) {
        var today = BrazilClock.today();
        if (customFrom != null || customTo != null) {
            if (customFrom == null || customTo == null || customFrom.isAfter(customTo) || customTo.isAfter(today)) {
                throw new InvalidDashboardPeriodException();
            }
            var window = window(PERIOD_CUSTOM, customFrom, customTo);
            if (window.days() > MAX_WINDOW_DAYS) throw new InvalidDashboardPeriodException();
            return window;
        }
        var requested = period == null || period.isBlank() ? PERIOD_7_DAYS : period.trim().toLowerCase();
        return switch (requested) {
            case PERIOD_7_DAYS -> window(PERIOD_7_DAYS, today.minusDays(6), today);
            case PERIOD_30_DAYS -> window(PERIOD_30_DAYS, today.minusDays(29), today);
            case PERIOD_ALL -> window(PERIOD_ALL, firstSignupDay(today), today);
            default -> throw new InvalidDashboardPeriodException();
        };
    }

    private static Window window(String period, LocalDate from, LocalDate to) {
        return new Window(period, from, to, ChronoUnit.DAYS.between(from, to) + 1);
    }

    private LocalDate firstSignupDay(LocalDate today) {
        return userRepository.findEarliestCreatedAt()
                .map(BrazilClock::fromUtc)
                .map(LocalDateTime::toLocalDate)
                .filter(firstDay -> !firstDay.isAfter(today))
                .orElse(today);
    }

    /** DAU/WAU/MAU are anchored at the window's END date, so a past window shows the habit of that moment. */
    private Engagement buildEngagement(LocalDate endDate, LocalDateTime from, LocalDateTime to, boolean includeInternal) {
        var activeInPeriod = receiptRepository.countActiveHouseholdsBetween(from, to, includeInternal);
        var dau = receiptRepository.countActiveHouseholdsBetween(BrazilClock.startOfDayUtc(endDate), to, includeInternal);
        var wau = receiptRepository.countActiveHouseholdsBetween(BrazilClock.startOfDayUtc(endDate.minusDays(6)), to, includeInternal);
        var mau = receiptRepository.countActiveHouseholdsBetween(BrazilClock.startOfDayUtc(endDate.minusDays(29)), to, includeInternal);
        return new Engagement(activeInPeriod, dau, wau, mau, rate(dau, mau));
    }

    private SignupFunnel buildFunnel(LocalDateTime from, LocalDateTime to, boolean includeInternal) {
        var signups = userRepository.countSignupsBetween(from, to, includeInternal);
        var verified = userRepository.countVerifiedBetween(from, to, includeInternal);
        var activated = userRepository.countActivatedBetween(from, to, includeInternal);
        return new SignupFunnel(signups, verified, activated, rate(verified, signups), rate(activated, signups));
    }

    /** One point per Brasília day of the window (zero-filled), signups split by acquisition channel. */
    private List<DailySignupLine> buildTimeline(Window window, LocalDateTime from, LocalDateTime to, boolean includeInternal) {
        var perDay = new TreeMap<LocalDate, Map<String, Long>>();
        for (var day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
            perDay.put(day, new LinkedHashMap<>());
        }
        for (var row : userRepository.signupTimelineBetween(from, to, includeInternal)) {
            var signupDay = BrazilClock.fromUtc((LocalDateTime) row[0]).toLocalDate();
            var channel = row[1] == null ? AcquisitionChannel.UNKNOWN.name() : ((AcquisitionChannel) row[1]).name();
            perDay.computeIfAbsent(signupDay, ignored -> new LinkedHashMap<>()).merge(channel, 1L, Long::sum);
        }
        var timeline = new ArrayList<DailySignupLine>();
        perDay.forEach((day, byChannel) -> timeline.add(new DailySignupLine(day,
                byChannel.values().stream().mapToLong(Long::longValue).sum(), byChannel)));
        return timeline;
    }

    /** Same merge as the overview's state cut, but only with the window's receipts. */
    private List<StateDistributionRow> buildByState(LocalDateTime from, LocalDateTime to, boolean includeInternal) {
        var countsByUf = new LinkedHashMap<String, long[]>(); // uf -> [receipts, users]
        var spendByUf = new HashMap<String, BigDecimal>();
        for (var row : receiptRepository.confirmedReceiptsByUfBetween(from, to, includeInternal)) {
            if (row[0] == null) continue;
            var uf = row[0].toString();
            countsByUf.computeIfAbsent(uf, ignored -> new long[2])[0] = ((Number) row[1]).longValue();
            spendByUf.put(uf, scale((BigDecimal) row[2]));
        }
        for (var row : receiptRepository.usersByPredominantUfBetween(from, to, includeInternal)) {
            if (row[0] == null) continue;
            countsByUf.computeIfAbsent(row[0].toString(), ignored -> new long[2])[1] = ((Number) row[1]).longValue();
        }
        var rows = new ArrayList<StateDistributionRow>();
        countsByUf.forEach((uf, counts) -> rows.add(new StateDistributionRow(uf, counts[0],
                spendByUf.getOrDefault(uf, BigDecimal.ZERO.setScale(2)), counts[1])));
        rows.sort((left, right) -> Long.compare(right.receipts(), left.receipts()));
        return rows;
    }

    /** Parsed (confirmed/pending/rejected) ÷ (parsed + failed); in-flight receipts don't count. */
    private double parseRate(LocalDateTime from, LocalDateTime to, boolean includeInternal) {
        var parsed = 0L;
        var failed = 0L;
        for (var row : receiptRepository.statusBreakdownBetween(from, to, includeInternal)) {
            var status = (ReceiptStatus) row[0];
            var count = ((Number) row[1]).longValue();
            switch (status) {
                case CONFIRMED, PENDING_CONFIRMATION, REJECTED -> parsed += count;
                case FAILED_PARSE -> failed += count;
                default -> { }
            }
        }
        return rate(parsed, parsed + failed);
    }

    private static double rate(long part, long whole) {
        return whole <= 0 ? 0d : BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
