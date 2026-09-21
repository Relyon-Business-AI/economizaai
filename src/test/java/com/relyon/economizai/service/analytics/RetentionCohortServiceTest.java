package com.relyon.economizai.service.analytics;

import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionCohortServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private RetentionCohortService service;

    private final LocalDate thisMonday = LocalDate.now().with(DayOfWeek.MONDAY);

    private Object[] size(LocalDate week, AcquisitionChannel channel, long count) {
        return new Object[]{Date.valueOf(week), channel.name(), count};
    }

    private Object[] active(LocalDate week, AcquisitionChannel channel, int offset, long count) {
        return new Object[]{Date.valueOf(week), channel.name(), offset, count};
    }

    @Test
    void buildsTriangleNewestFirstWithOnlyObservableWeeks() {
        var cohortA = thisMonday.minusWeeks(3);  // age 3 — full row
        var cohortB = thisMonday.minusWeeks(1);  // age 1
        var cohortC = thisMonday;                // age 0 — signup week only

        when(userRepository.cohortSizesSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                size(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 10),
                size(cohortB, AcquisitionChannel.ORGANIC, 4),
                size(cohortC, AcquisitionChannel.ORGANIC, 3)));
        when(userRepository.cohortActivitySince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 0, 10),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 1, 5),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 2, 2),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 3, 1),
                active(cohortB, AcquisitionChannel.ORGANIC, 0, 4),
                active(cohortB, AcquisitionChannel.ORGANIC, 1, 1),
                active(cohortC, AcquisitionChannel.ORGANIC, 0, 3)));

        var report = service.cohorts(4, false);

        assertThat(report.weeks()).isEqualTo(4);
        // Newest first: C, B, A.
        assertThat(report.cohorts()).extracting("weekStart")
                .containsExactly(thisMonday, thisMonday.minusWeeks(1), thisMonday.minusWeeks(3));
        assertThat(report.cohorts().get(0).activeByWeek()).containsExactly(3L);            // age 0
        assertThat(report.cohorts().get(1).activeByWeek()).containsExactly(4L, 1L);        // age 1
        assertThat(report.cohorts().get(2).activeByWeek()).containsExactly(10L, 5L, 2L, 1L); // age 3
        assertThat(report.cohorts().get(2).size()).isEqualTo(10L);
    }

    @Test
    void pooledChannelCurveOnlyCountsCohortsOldEnoughForEachOffset() {
        var cohortA = thisMonday.minusWeeks(3);
        var cohortB = thisMonday.minusWeeks(1);
        var cohortC = thisMonday;

        when(userRepository.cohortSizesSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                size(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 10),
                size(cohortB, AcquisitionChannel.ORGANIC, 4),
                size(cohortC, AcquisitionChannel.ORGANIC, 3)));
        when(userRepository.cohortActivitySince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 0, 10),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 1, 5),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 2, 2),
                active(cohortA, AcquisitionChannel.INSTAGRAM_PAID, 3, 1),
                active(cohortB, AcquisitionChannel.ORGANIC, 0, 4),
                active(cohortB, AcquisitionChannel.ORGANIC, 1, 1),
                active(cohortC, AcquisitionChannel.ORGANIC, 0, 3)));

        var report = service.cohorts(4, false);

        // Sorted by total users desc: INSTAGRAM_PAID (10), then ORGANIC (7).
        var paid = report.byChannel().get(0);
        assertThat(paid.channel()).isEqualTo("INSTAGRAM_PAID");
        assertThat(paid.users()).isEqualTo(10L);
        assertThat(paid.retentionByWeek()).containsExactly(1.0, 0.5, 0.2, 0.1);

        var organic = report.byChannel().get(1);
        assertThat(organic.channel()).isEqualTo("ORGANIC");
        assertThat(organic.users()).isEqualTo(7L);
        // offset0: (4+3)/(4+3)=1.0; offset1: only B mature → 1/4=0.25; offset2+: no mature cohort → 0.
        assertThat(organic.retentionByWeek()).containsExactly(1.0, 0.25, 0.0, 0.0);
    }

    @Test
    void clampsWeeksIntoAllowedRangeAndHandlesNoData() {
        when(userRepository.cohortSizesSince(any(), anyBoolean())).thenReturn(List.of());
        when(userRepository.cohortActivitySince(any(), anyBoolean())).thenReturn(List.of());

        var tooFew = service.cohorts(1, false);
        var tooMany = service.cohorts(999, false);

        assertThat(tooFew.weeks()).isEqualTo(4);   // clamped up to MIN
        assertThat(tooMany.weeks()).isEqualTo(16); // clamped down to MAX
        assertThat(tooFew.cohorts()).isEmpty();
        assertThat(tooFew.byChannel()).isEmpty();
    }

    @Test
    void nullChannelBucketsAsUnknown() {
        when(userRepository.cohortSizesSince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{Date.valueOf(thisMonday), null, 5L}));
        when(userRepository.cohortActivitySince(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{Date.valueOf(thisMonday), null, 0, 5L}));

        var report = service.cohorts(4, false);

        assertThat(report.byChannel()).extracting("channel").containsExactly(AcquisitionChannel.UNKNOWN.name());
    }
}
