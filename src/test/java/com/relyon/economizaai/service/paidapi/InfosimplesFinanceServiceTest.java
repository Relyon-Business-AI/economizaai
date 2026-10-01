package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.model.InfosimplesAccountSnapshot;
import com.relyon.economizaai.model.InfosimplesMonthHistory;
import com.relyon.economizaai.repository.InfosimplesAccountSnapshotRepository;
import com.relyon.economizaai.repository.InfosimplesMonthHistoryRepository;
import com.relyon.economizaai.service.sefaz.InfosimplesService;
import com.relyon.economizaai.service.sefaz.InfosimplesService.InfosimplesAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InfosimplesFinanceServiceTest {

    @Mock private InfosimplesAccountSnapshotRepository snapshotRepository;
    @Mock private InfosimplesMonthHistoryRepository historyRepository;
    @Mock private InfosimplesService infosimples;
    @Mock private InfosimplesAlertService alertService;

    private InfosimplesFinanceService service() {
        return new InfosimplesFinanceService(snapshotRepository, historyRepository,
                Optional.of(infosimples), alertService);
    }

    private static InfosimplesMonthHistory month(String month, String recarga, String consumo,
                                                 String varrido, boolean closed) {
        return InfosimplesMonthHistory.builder()
                .month(month)
                .recarga(new BigDecimal(recarga))
                .consumo(consumo == null ? null : new BigDecimal(consumo))
                .varrido(varrido == null ? null : new BigDecimal(varrido))
                .closed(closed)
                .build();
    }

    @Test
    void finance_buildsTotalsAndSweepForecast() {
        when(infosimples.fetchAccount()).thenReturn(Optional.of(new InfosimplesAccount(
                new BigDecimal("92.32"), new BigDecimal("7.68"), new BigDecimal("100"))));
        when(historyRepository.findAllByOrderByMonthDesc()).thenReturn(List.of(
                month("2026-09", "100.00", null, null, false),
                month("2026-08", "0.00", "0.00", "0.00", true),
                month("2026-07", "100.00", "11.36", "88.64", true)));

        var finance = service().finance();

        assertEquals(new BigDecimal("92.32"), finance.saldo());
        // Sweep forecast: franquia 100 - consumo 7.68 = 92.32, capped at saldo.
        assertEquals(0, new BigDecimal("92.32").compareTo(finance.aVarrerNoFechamento()));
        assertEquals(1, finance.proximoFechamento().getDayOfMonth());
        assertEquals(0, new BigDecimal("200.00").compareTo(finance.totalRecarregado()));
        assertEquals(0, new BigDecimal("11.36").compareTo(finance.totalConsumido()));
        assertEquals(0, new BigDecimal("88.64").compareTo(finance.totalVarrido()));
        assertEquals(3, finance.historico().size());
    }

    @Test
    void finance_liveFieldsNullWhenProviderUnreachable() {
        when(infosimples.fetchAccount()).thenReturn(Optional.empty());
        when(historyRepository.findAllByOrderByMonthDesc()).thenReturn(List.of());

        var finance = service().finance();

        assertNull(finance.saldo());
        assertNull(finance.aVarrerNoFechamento());
        assertEquals(0, BigDecimal.ZERO.compareTo(finance.totalRecarregado()));
    }

    @Test
    void takeDailySnapshot_detectsRecargaFromBalanceJump() {
        when(infosimples.fetchAccount()).thenReturn(Optional.of(new InfosimplesAccount(
                new BigDecimal("150.00"), new BigDecimal("10.00"), new BigDecimal("100"))));
        var yesterday = InfosimplesAccountSnapshot.builder()
                .takenAt(OffsetDateTime.now().minusDays(1))
                .balance(new BigDecimal("52.00"))
                .currentUsage(new BigDecimal("8.00"))
                .minBill(new BigDecimal("100"))
                .build();
        when(snapshotRepository.findTopByOrderByTakenAtDesc()).thenReturn(Optional.of(yesterday));
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.findByMonth(any())).thenReturn(Optional.empty());

        service().takeDailySnapshot();

        // 150 - 52 + (10-8 usados) = recarga de 100 detectada.
        var savedRow = ArgumentCaptor.forClass(InfosimplesMonthHistory.class);
        verify(historyRepository).save(savedRow.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(savedRow.getValue().getRecarga()));
    }

    @Test
    void takeDailySnapshot_noRecargaWhenBalanceOnlyShrinks() {
        when(infosimples.fetchAccount()).thenReturn(Optional.of(new InfosimplesAccount(
                new BigDecimal("90.00"), new BigDecimal("10.00"), new BigDecimal("100"))));
        var yesterday = InfosimplesAccountSnapshot.builder()
                .takenAt(OffsetDateTime.now().minusDays(1))
                .balance(new BigDecimal("92.00"))
                .currentUsage(new BigDecimal("8.00"))
                .minBill(new BigDecimal("100"))
                .build();
        when(snapshotRepository.findTopByOrderByTakenAtDesc()).thenReturn(Optional.of(yesterday));
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().takeDailySnapshot();

        verify(historyRepository, never()).save(any());
    }

    @Test
    void takeDailySnapshot_alertsWhenAccountUnreachable() {
        when(infosimples.fetchAccount()).thenReturn(Optional.empty());

        service().takeDailySnapshot();

        verify(alertService).alertAccountUnreachable();
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void takeDailySnapshot_handsBalanceToLowBalanceCheck() {
        when(infosimples.fetchAccount()).thenReturn(Optional.of(new InfosimplesAccount(
                new BigDecimal("3.50"), new BigDecimal("96.50"), new BigDecimal("100"))));
        when(snapshotRepository.findTopByOrderByTakenAtDesc()).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().takeDailySnapshot();

        verify(alertService).checkLowBalance(new BigDecimal("3.50"));
    }

    @Test
    void closePreviousMonth_freezesConsumoAndSweepFromLastSnapshot() {
        var openMonth = month("2026-09", "100.00", null, null, false);
        when(historyRepository.findByMonth(any())).thenReturn(Optional.of(openMonth));
        when(snapshotRepository.findTopByTakenAtBeforeOrderByTakenAtDesc(any())).thenReturn(Optional.of(
                InfosimplesAccountSnapshot.builder()
                        .takenAt(OffsetDateTime.now().minusHours(8))
                        .balance(new BigDecimal("92.32"))
                        .currentUsage(new BigDecimal("7.68"))
                        .minBill(new BigDecimal("100"))
                        .build()));

        service().closePreviousMonth();

        assertTrue(openMonth.isClosed());
        assertEquals(0, new BigDecimal("7.68").compareTo(openMonth.getConsumo()));
        assertEquals(0, new BigDecimal("92.32").compareTo(openMonth.getVarrido()));
        verify(historyRepository).save(openMonth);
    }

    @Test
    void closePreviousMonth_idempotentWhenAlreadyClosed() {
        when(historyRepository.findByMonth(any())).thenReturn(Optional.of(
                month("2026-08", "0.00", "0.00", "0.00", true)));

        service().closePreviousMonth();

        verify(historyRepository, never()).save(any());
    }
}
