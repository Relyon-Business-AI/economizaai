package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.dto.response.InfosimplesFinanceResponse;
import com.relyon.economizaai.dto.response.InfosimplesFinanceResponse.MonthLine;
import com.relyon.economizaai.model.InfosimplesAccountSnapshot;
import com.relyon.economizaai.model.InfosimplesMonthHistory;
import com.relyon.economizaai.repository.InfosimplesAccountSnapshotRepository;
import com.relyon.economizaai.repository.InfosimplesMonthHistoryRepository;
import com.relyon.economizaai.service.sefaz.InfosimplesService;
import com.relyon.economizaai.service.sefaz.InfosimplesService.InfosimplesAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Financial ledger of the Infosimples PREPAID account. The provider's API only
 * exposes the LIVE state (saldo, consumo do mês, franquia — free endpoint), so
 * history is built here: a daily snapshot job records the account and detects
 * recargas; a month-close job (day 1, after the provider's ~06h franchise sweep)
 * freezes the previous month's consumo and how much the franquia varreu.
 * Jul-set/2026 rows were seeded from the painel extratos in V87.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfosimplesFinanceService {

    private static final ZoneId BR = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final InfosimplesAccountSnapshotRepository snapshotRepository;
    private final InfosimplesMonthHistoryRepository historyRepository;
    private final Optional<InfosimplesService> infosimples;
    private final InfosimplesAlertService alertService;
    private final Clock clock = Clock.system(BR);

    /** The admin panel payload: live account + month ledger + lifetime totals. */
    public InfosimplesFinanceResponse finance() {
        var account = infosimples.flatMap(InfosimplesService::fetchAccount).orElse(null);
        var history = historyRepository.findAllByOrderByMonthDesc();
        var totalRecarregado = sum(history, InfosimplesMonthHistory::getRecarga);
        var totalConsumido = sum(history, InfosimplesMonthHistory::getConsumo);
        var totalVarrido = sum(history, InfosimplesMonthHistory::getVarrido);

        BigDecimal aVarrer = null;
        if (account != null && account.balance() != null && account.currentUsage() != null
                && account.minBill() != null) {
            // What the franchise will sweep at month close: the gap up to the
            // franquia, capped at the available saldo.
            aVarrer = account.minBill().subtract(account.currentUsage())
                    .max(BigDecimal.ZERO).min(account.balance());
        }
        var fechamento = LocalDate.now(clock).plusMonths(1).withDayOfMonth(1);
        var lines = history.stream()
                .map(row -> new MonthLine(row.getMonth(), row.getRecarga(), row.getConsumo(),
                        row.getVarrido(), row.isClosed()))
                .toList();
        return new InfosimplesFinanceResponse(
                account == null ? null : account.balance(),
                account == null ? null : account.currentUsage(),
                account == null ? null : account.minBill(),
                aVarrer,
                fechamento,
                totalRecarregado,
                totalConsumido,
                totalVarrido,
                lines);
    }

    /**
     * Daily reading of the live account. Also detects a RECARGA: the balance
     * grew more than the usage delta explains → credit was added; the surplus is
     * accumulated on the current month's row.
     */
    @Scheduled(cron = "${economizaai.infosimples.snapshot-cron:0 40 23 * * *}", zone = "America/Sao_Paulo")
    @Transactional
    public void takeDailySnapshot() {
        if (infosimples.isEmpty()) return; // integration disabled — nothing to watch
        var account = infosimples.flatMap(InfosimplesService::fetchAccount).orElse(null);
        if (account == null) {
            // Enabled but unreadable (API down / token revogado) — a CE outage
            // could hide behind this, so the owner hears about it.
            alertService.alertAccountUnreachable();
            return;
        }
        alertService.checkLowBalance(account.balance());
        var previous = snapshotRepository.findTopByOrderByTakenAtDesc().orElse(null);
        var snapshot = snapshotRepository.save(InfosimplesAccountSnapshot.builder()
                .takenAt(OffsetDateTime.now(clock))
                .balance(account.balance())
                .currentUsage(account.currentUsage())
                .minBill(account.minBill())
                .build());
        detectRecarga(previous, snapshot);
        log.info("infosimples.finance.snapshot saldo={} consumoMes={}", account.balance(), account.currentUsage());
    }

    /**
     * Freezes the month that just ended, using its LAST snapshot (taken before
     * the provider's ~06h sweep on day 1). varrido = franquia - consumo, capped
     * at the saldo that existed. Runs after the sweep so the live saldo already
     * reflects it; idempotent via the closed flag.
     */
    @Scheduled(cron = "${economizaai.infosimples.month-close-cron:0 30 7 1 * *}", zone = "America/Sao_Paulo")
    @Transactional
    public void closePreviousMonth() {
        var firstOfMonth = LocalDate.now(clock).withDayOfMonth(1);
        var previousMonth = firstOfMonth.minusMonths(1).format(MONTH);
        var row = historyRepository.findByMonth(previousMonth)
                .orElseGet(() -> InfosimplesMonthHistory.builder()
                        .month(previousMonth).recarga(BigDecimal.ZERO).closed(false).build());
        if (row.isClosed()) return;
        var cutoff = firstOfMonth.atStartOfDay(BR).toOffsetDateTime();
        var lastSnapshot = snapshotRepository.findTopByTakenAtBeforeOrderByTakenAtDesc(cutoff).orElse(null);
        if (lastSnapshot == null || lastSnapshot.getCurrentUsage() == null) {
            log.warn("infosimples.finance.close_skipped month={} — no snapshot to close from", previousMonth);
            return;
        }
        var consumo = lastSnapshot.getCurrentUsage();
        var franquia = lastSnapshot.getMinBill() == null ? BigDecimal.ZERO : lastSnapshot.getMinBill();
        var saldo = lastSnapshot.getBalance() == null ? BigDecimal.ZERO : lastSnapshot.getBalance();
        row.setConsumo(consumo);
        row.setVarrido(franquia.subtract(consumo).max(BigDecimal.ZERO).min(saldo));
        row.setClosed(true);
        historyRepository.save(row);
        log.info("infosimples.finance.month_closed month={} consumo={} varrido={}",
                previousMonth, row.getConsumo(), row.getVarrido());
    }

    private void detectRecarga(InfosimplesAccountSnapshot previous, InfosimplesAccountSnapshot current) {
        if (previous == null || previous.getBalance() == null || current.getBalance() == null
                || previous.getCurrentUsage() == null || current.getCurrentUsage() == null) {
            return;
        }
        // Balance can only grow via a recarga; usage growth explains shrinkage.
        var usageDelta = current.getCurrentUsage().subtract(previous.getCurrentUsage()).max(BigDecimal.ZERO);
        var recarga = current.getBalance().subtract(previous.getBalance()).add(usageDelta);
        if (recarga.compareTo(BigDecimal.ONE) < 0) return; // ignore sub-R$1 noise
        var month = LocalDate.now(clock).format(MONTH);
        var row = historyRepository.findByMonth(month)
                .orElseGet(() -> InfosimplesMonthHistory.builder()
                        .month(month).recarga(BigDecimal.ZERO).closed(false).build());
        row.setRecarga(row.getRecarga().add(recarga));
        historyRepository.save(row);
        log.info("infosimples.finance.recarga_detected month={} valor={}", month, recarga);
    }

    private static BigDecimal sum(List<InfosimplesMonthHistory> rows,
                                  Function<InfosimplesMonthHistory, BigDecimal> field) {
        return rows.stream().map(field).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
