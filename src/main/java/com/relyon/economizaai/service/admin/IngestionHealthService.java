package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.IngestionHealthResponse;
import com.relyon.economizaai.dto.response.IngestionHealthResponse.ErrorLine;
import com.relyon.economizaai.dto.response.IngestionHealthResponse.UfOutcomeLine;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
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
import java.util.Map;

/**
 * Read-only pipeline-health report for the ops dashboard, derived entirely from
 * the {@code receipts} table (status + uf + parseErrorReason). A "parsed" receipt
 * is one the pipeline produced items for (confirmed/pending/rejected); FAILED_PARSE
 * is a failure; PROCESSING / NEEDS_DEVICE_FETCH are in-flight and excluded from the
 * success rate. Stuck counts are the two sweeper timeout reasons.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionHealthService {

    private static final String PROCESSING_TIMEOUT = "receipt.processing.timeout";
    private static final String DEVICE_FETCH_TIMEOUT = "receipt.device_fetch.timeout";

    private final ReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public IngestionHealthResponse report(int days) {
        var windowDays = Math.max(1, days);
        var since = LocalDate.now().minusDays(windowDays - 1L).atStartOfDay();

        var byStatus = new LinkedHashMap<String, Long>();
        for (var row : receiptRepository.statusBreakdownSince(since)) {
            byStatus.put(((ReceiptStatus) row[0]).name(), ((Number) row[1]).longValue());
        }
        var total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        var parsedOk = statusCount(byStatus, ReceiptStatus.CONFIRMED)
                + statusCount(byStatus, ReceiptStatus.PENDING_CONFIRMATION)
                + statusCount(byStatus, ReceiptStatus.REJECTED);
        var failedParse = statusCount(byStatus, ReceiptStatus.FAILED_PARSE);
        var inFlight = statusCount(byStatus, ReceiptStatus.PROCESSING)
                + statusCount(byStatus, ReceiptStatus.NEEDS_DEVICE_FETCH);

        var byUf = buildUfOutcomes(since);
        var topErrors = new ArrayList<ErrorLine>();
        var stuckProcessing = 0L;
        var stuckDeviceFetch = 0L;
        for (var row : receiptRepository.errorReasonBreakdownSince(since)) {
            var reason = row[0] == null ? "(unknown)" : row[0].toString();
            var count = ((Number) row[1]).longValue();
            topErrors.add(new ErrorLine(reason, count));
            if (PROCESSING_TIMEOUT.equals(reason)) stuckProcessing = count;
            if (DEVICE_FETCH_TIMEOUT.equals(reason)) stuckDeviceFetch = count;
        }

        log.info("ops.ingestion_health days={} total={} parsed={} failed={} successRate={}",
                windowDays, total, parsedOk, failedParse, rate(parsedOk, parsedOk + failedParse));
        return new IngestionHealthResponse(windowDays, total, byStatus, parsedOk, failedParse, inFlight,
                rate(parsedOk, parsedOk + failedParse), stuckProcessing, stuckDeviceFetch, byUf, topErrors);
    }

    private ArrayList<UfOutcomeLine> buildUfOutcomes(LocalDateTime since) {
        var ufAggregate = new LinkedHashMap<String, long[]>(); // [total, parsed, failed]
        for (var row : receiptRepository.ufStatusBreakdownSince(since)) {
            var uf = row[0] == null ? "??" : ((UnidadeFederativa) row[0]).name();
            var status = (ReceiptStatus) row[1];
            var count = ((Number) row[2]).longValue();
            var accumulator = ufAggregate.computeIfAbsent(uf, ignored -> new long[3]);
            accumulator[0] += count;
            if (status == ReceiptStatus.FAILED_PARSE) {
                accumulator[2] += count;
            } else if (status == ReceiptStatus.CONFIRMED || status == ReceiptStatus.PENDING_CONFIRMATION
                    || status == ReceiptStatus.REJECTED) {
                accumulator[1] += count;
            }
        }
        var lines = new ArrayList<UfOutcomeLine>();
        ufAggregate.forEach((uf, accumulator) -> lines.add(new UfOutcomeLine(uf,
                accumulator[0], accumulator[1], accumulator[2], rate(accumulator[1], accumulator[1] + accumulator[2]))));
        lines.sort((left, right) -> Long.compare(right.total(), left.total()));
        return lines;
    }

    private static long statusCount(Map<String, Long> byStatus, ReceiptStatus status) {
        return byStatus.getOrDefault(status.name(), 0L);
    }

    private static double rate(long part, long whole) {
        return whole <= 0 ? 0d : BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP).doubleValue();
    }
}
