package com.relyon.economizai.service.analytics;

import com.relyon.economizai.dto.response.RetentionCohortResponse;
import com.relyon.economizai.dto.response.RetentionCohortResponse.ChannelCurve;
import com.relyon.economizai.dto.response.RetentionCohortResponse.CohortWeek;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the weekly cohort retention triangle from two aggregate queries (cohort
 * sizes + activity grid). A cohort is everyone who signed up in the same ISO week;
 * retention at offset k is the share of that cohort active (scanned a receipt) k
 * weeks later. Denominators for the per-channel pooled curve only count cohorts old
 * enough to have observed week k, so a fresh cohort never drags the tail down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCohortService {

    private static final int DEFAULT_WEEKS = 8;
    private static final int MIN_WEEKS = 4;
    private static final int MAX_WEEKS = 16;

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public RetentionCohortResponse cohorts(int weeks, boolean includeInternal) {
        var span = Math.min(MAX_WEEKS, Math.max(MIN_WEEKS, weeks));
        var currentWeekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        var oldestCohort = currentWeekStart.minusWeeks(span - 1L);
        var since = oldestCohort.atStartOfDay();

        var sizeByWeekChannel = new LinkedHashMap<LocalDate, Map<String, Long>>();
        for (var row : userRepository.cohortSizesSince(since, includeInternal)) {
            var weekStart = toLocalDate(row[0]);
            var channel = channelName(row[1]);
            sizeByWeekChannel.computeIfAbsent(weekStart, ignored -> new LinkedHashMap<>())
                    .merge(channel, toLong(row[2]), Long::sum);
        }

        // (weekStart, channel, offset) -> distinct active users.
        var activity = new LinkedHashMap<LocalDate, Map<String, Map<Integer, Long>>>();
        for (var row : userRepository.cohortActivitySince(since, includeInternal)) {
            var weekStart = toLocalDate(row[0]);
            var channel = channelName(row[1]);
            var offset = toInt(row[2]);
            if (offset < 0 || offset >= span) {
                continue;
            }
            activity.computeIfAbsent(weekStart, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(channel, ignored -> new LinkedHashMap<>())
                    .merge(offset, toLong(row[3]), Long::sum);
        }

        var cohortRows = buildCohortRows(sizeByWeekChannel, activity, currentWeekStart, span);
        var channelCurves = buildChannelCurves(sizeByWeekChannel, activity, currentWeekStart, span);

        log.info("analytics.retention_cohorts weeks={} includeInternal={} cohorts={} channels={}",
                span, includeInternal, cohortRows.size(), channelCurves.size());
        return new RetentionCohortResponse(span, cohortRows, channelCurves);
    }

    /** One row per signup week (newest first); each spans only the weeks it's old enough to have observed. */
    private List<CohortWeek> buildCohortRows(Map<LocalDate, Map<String, Long>> sizeByWeekChannel,
                                             Map<LocalDate, Map<String, Map<Integer, Long>>> activity,
                                             LocalDate currentWeekStart, int span) {
        var rows = new ArrayList<CohortWeek>();
        for (var weekEntry : new TreeMap<>(sizeByWeekChannel).entrySet()) {
            var weekStart = weekEntry.getKey();
            var size = weekEntry.getValue().values().stream().mapToLong(Long::longValue).sum();
            var age = (int) ChronoUnit.WEEKS.between(weekStart, currentWeekStart);
            var observableWeeks = Math.min(span, age + 1);
            var activeByWeek = new ArrayList<Long>();
            for (var offset = 0; offset < observableWeeks; offset++) {
                activeByWeek.add(activeAt(activity, weekStart, offset));
            }
            rows.add(new CohortWeek(weekStart, size, activeByWeek));
        }
        rows.sort((left, right) -> right.weekStart().compareTo(left.weekStart()));
        return rows;
    }

    /** Per-channel pooled retention: offset k averaged over cohorts old enough to observe it. */
    private List<ChannelCurve> buildChannelCurves(Map<LocalDate, Map<String, Long>> sizeByWeekChannel,
                                                  Map<LocalDate, Map<String, Map<Integer, Long>>> activity,
                                                  LocalDate currentWeekStart, int span) {
        var channels = new LinkedHashMap<String, long[]>(); // channel -> total users (index 0 only; curve computed below)
        for (var byChannel : sizeByWeekChannel.values()) {
            byChannel.forEach((channel, size) -> channels.computeIfAbsent(channel, ignored -> new long[1])[0] += size);
        }
        var curves = new ArrayList<ChannelCurve>();
        for (var channel : channels.keySet()) {
            var retentionByWeek = new ArrayList<Double>();
            for (var offset = 0; offset < span; offset++) {
                var active = 0L;
                var eligible = 0L;
                for (var weekEntry : sizeByWeekChannel.entrySet()) {
                    var weekStart = weekEntry.getKey();
                    var age = (int) ChronoUnit.WEEKS.between(weekStart, currentWeekStart);
                    if (age < offset) {
                        continue; // cohort too fresh to have observed this offset
                    }
                    var channelSize = weekEntry.getValue().getOrDefault(channel, 0L);
                    if (channelSize == 0) {
                        continue;
                    }
                    eligible += channelSize;
                    active += activeAtForChannel(activity, weekStart, channel, offset);
                }
                retentionByWeek.add(rate(active, eligible));
            }
            curves.add(new ChannelCurve(channel, channels.get(channel)[0], retentionByWeek));
        }
        curves.sort((left, right) -> Long.compare(right.users(), left.users()));
        return curves;
    }

    private static long activeAt(Map<LocalDate, Map<String, Map<Integer, Long>>> activity,
                                 LocalDate weekStart, int offset) {
        var byChannel = activity.get(weekStart);
        if (byChannel == null) {
            return 0L;
        }
        return byChannel.values().stream()
                .mapToLong(perOffset -> perOffset.getOrDefault(offset, 0L))
                .sum();
    }

    private static long activeAtForChannel(Map<LocalDate, Map<String, Map<Integer, Long>>> activity,
                                           LocalDate weekStart, String channel, int offset) {
        var byChannel = activity.get(weekStart);
        if (byChannel == null) {
            return 0L;
        }
        var perOffset = byChannel.get(channel);
        return perOffset == null ? 0L : perOffset.getOrDefault(offset, 0L);
    }

    private static double rate(long part, long whole) {
        return whole <= 0 ? 0d : BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String channelName(Object channel) {
        return channel == null ? AcquisitionChannel.UNKNOWN.name() : channel.toString();
    }

    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case Date sqlDate -> sqlDate.toLocalDate();
            case LocalDate localDate -> localDate;
            case java.time.LocalDateTime dateTime -> dateTime.toLocalDate();
            case java.time.OffsetDateTime offset -> offset.toLocalDate();
            case java.time.Instant instant -> instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            default -> throw new IllegalStateException("Unexpected cohort_week type: " + value.getClass());
        };
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
