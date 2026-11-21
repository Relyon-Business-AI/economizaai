package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.model.OfficialInflationMonth;
import com.relyon.economizaai.repository.OfficialInflationMonthRepository;
import com.relyon.economizaai.service.inflation.SidraInflationClient.OfficialReading;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Brings the official months from IBGE into the database. The fetch runs outside any
 * transaction; what came back is stored in one short one. Loading the same months again only
 * updates the numbers IBGE revised.
 */
@Slf4j
@Service
public class OfficialInflationLoader {

    /** The area code IBGE gives Brasil. */
    public static final String NATIONAL_AREA_CODE = "1";

    /** {@code newestMonthIsNew}: the newest national month was not in the database before this load. */
    public record LoadResult(int readingsFetched, int rowsStored, Optional<YearMonth> newestNationalMonth,
                             boolean newestMonthIsNew) {

        static LoadResult nothingFetched() {
            return new LoadResult(0, 0, Optional.empty(), false);
        }
    }

    private final SidraInflationClient sidraClient;
    private final OfficialInflationMonthRepository officialRepository;
    private final PriceInflationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OfficialInflationLoader(SidraInflationClient sidraClient, OfficialInflationMonthRepository officialRepository,
                                   PriceInflationProperties properties, PlatformTransactionManager transactionManager) {
        this.sidraClient = sidraClient;
        this.officialRepository = officialRepository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean hasNoOfficialMonth() {
        return officialRepository.count() == 0;
    }

    public LoadResult loadLastMonths(int months) {
        var readings = sidraClient.fetchMonthlyChanges(months);
        if (readings.isEmpty()) {
            log.warn("price_inflation.official.nothing_fetched months={}", months);
            return LoadResult.nothingFetched();
        }
        var newestBefore = newestNationalMonth();
        var rowsStored = transactionTemplate.execute(status -> store(readings));
        var newestAfter = newestNationalMonth();
        var newestIsNew = newestAfter.isPresent()
                && newestBefore.map(before -> newestAfter.get().isAfter(before)).orElse(true);
        log.info("price_inflation.official.loaded readings={} stored={} newest={} new_month={}",
                readings.size(), rowsStored, newestAfter.map(YearMonth::toString).orElse("-"), newestIsNew);
        return new LoadResult(readings.size(), rowsStored == null ? 0 : rowsStored, newestAfter, newestIsNew);
    }

    public Optional<YearMonth> newestNationalMonth() {
        return officialRepository.latestMonth(NATIONAL_AREA_CODE, properties.getSidra().getFoodAtHomeItem())
                .map(YearMonth::from);
    }

    private int store(List<OfficialReading> readings) {
        var oldestMonth = readings.stream().map(OfficialReading::month).min(YearMonth::compareTo).orElseThrow();
        var existingByKey = new HashMap<String, OfficialInflationMonth>();
        officialRepository.findAllByReferenceMonthGreaterThanEqual(oldestMonth.atDay(1))
                .forEach(existing -> existingByKey.put(
                        key(existing.getAreaCode(), existing.getItemCode(), existing.getReferenceMonth()), existing));

        var toSave = new ArrayList<OfficialInflationMonth>();
        for (var reading : readings) {
            var referenceMonth = reading.month().atDay(1);
            var existing = existingByKey.get(key(reading.areaCode(), reading.itemCode(), referenceMonth));
            if (existing == null) {
                var created = newMonth(reading, referenceMonth);
                existingByKey.put(key(reading.areaCode(), reading.itemCode(), referenceMonth), created);
                toSave.add(created);
            } else if (existing.getChangePercent().compareTo(reading.changePercent()) != 0) {
                existing.setChangePercent(reading.changePercent());
                toSave.add(existing);
            }
        }
        officialRepository.saveAll(toSave);
        return toSave.size();
    }

    private static OfficialInflationMonth newMonth(OfficialReading reading, LocalDate referenceMonth) {
        return OfficialInflationMonth.builder()
                .areaCode(reading.areaCode())
                .areaName(reading.areaName())
                .state(reading.state())
                .itemCode(reading.itemCode())
                .itemName(reading.itemName())
                .referenceMonth(referenceMonth)
                .changePercent(reading.changePercent())
                .build();
    }

    private static String key(String areaCode, String itemCode, LocalDate referenceMonth) {
        return areaCode + "|" + itemCode + "|" + referenceMonth;
    }
}
