package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.dto.response.PriceInflationRecomputeResponse;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;

/**
 * Keeps "Preços e inflação" up to date once a day: brings the official months from IBGE,
 * recomputes the community's price changes of the last closed months and, the first time a new
 * official month shows up, sends the monthly notice. The first run ever fills the history and
 * announces nothing. Nothing here holds a transaction across the IBGE call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceInflationRefreshJob {

    private static final int MAX_MONTHS_ON_DEMAND = 120;

    private final OfficialInflationLoader officialLoader;
    private final CommunityPriceChangeComputer communityComputer;
    private final PriceInflationNoticeService noticeService;
    private final PriceInflationProperties properties;

    @Scheduled(cron = "${economizaai.price-inflation.job-cron:0 20 5 * * *}", zone = "America/Sao_Paulo")
    public void refreshDaily() {
        try {
            var firstOfficialLoad = officialLoader.hasNoOfficialMonth();
            var officialMonths = firstOfficialLoad
                    ? properties.getOfficialBackfillMonths() : properties.getOfficialRefreshMonths();
            var communityMonths = communityComputer.hasNoMonth()
                    ? properties.getCommunityBackfillMonths() : properties.getCommunityRefreshMonths();

            var loaded = officialLoader.loadLastMonths(officialMonths);
            communityComputer.recomputeMonthsEndingAt(lastClosedMonth(), communityMonths);
            if (loaded.newestMonthIsNew() && !firstOfficialLoad) {
                loaded.newestNationalMonth().ifPresent(noticeService::announce);
            }
        } catch (RuntimeException failure) {
            log.error("price_inflation.refresh.failed {}: {}", failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    /** Loads and recomputes the last {@code months} months on demand. Never sends the notice. */
    public PriceInflationRecomputeResponse refreshOnDemand(Integer requestedMonths) {
        var months = Math.clamp(requestedMonths == null ? properties.getCommunityBackfillMonths() : requestedMonths,
                1, MAX_MONTHS_ON_DEMAND);
        var loaded = officialLoader.loadLastMonths(months);
        var recomputed = communityComputer.recomputeMonthsEndingAt(lastClosedMonth(), months);
        log.info("price_inflation.refresh.on_demand months={} official_stored={} community_rows={}",
                months, loaded.rowsStored(), recomputed.productRows());
        return new PriceInflationRecomputeResponse(months, loaded.readingsFetched(), loaded.rowsStored(),
                loaded.newestNationalMonth().map(YearMonth::toString).orElse(null),
                recomputed.months(), recomputed.productRows(), recomputed.pairs());
    }

    private static YearMonth lastClosedMonth() {
        return BrazilClock.currentYearMonth().minusMonths(1);
    }
}
