package com.relyon.economizaai.service.paidapi.balance;

import com.relyon.economizaai.model.ProviderBalanceState;
import com.relyon.economizaai.repository.ProviderBalanceStateRepository;
import com.relyon.economizaai.service.paidapi.CostAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderBalanceMonitorTest {

    private static final Instant T0 = Instant.parse("2026-11-11T15:05:00Z");

    @Mock private ProviderBalanceStateRepository stateRepository;
    @Mock private CostAlertService costAlertService;

    private final Map<String, ProviderBalanceState> storedStates = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(costAlertService.isEnabled()).thenReturn(true);
        lenient().when(stateRepository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedStates.get(invocation.<String>getArgument(0))));
        lenient().when(stateRepository.save(any(ProviderBalanceState.class))).thenAnswer(invocation -> {
            var state = invocation.<ProviderBalanceState>getArgument(0);
            storedStates.put(state.getProvider(), state);
            return state;
        });
    }

    /** Scriptable provider: the test sets the next balance reading. */
    private static class FakeSource implements ProviderBalanceSource {
        private final String key;
        private boolean configured = true;
        private BigDecimal balance;
        private BigDecimal credit;
        private RuntimeException failure;

        FakeSource(String key) {
            this.key = key;
        }

        void balance(String value) {
            this.balance = new BigDecimal(value);
        }

        @Override public String providerKey() { return key; }
        @Override public String displayName() { return "CapSolver"; }
        @Override public String currencySymbol() { return "US$"; }
        @Override public boolean isConfigured() { return configured; }
        @Override public BigDecimal exhaustedAtOrBelow() { return new BigDecimal("0.01"); }
        @Override public BigDecimal minimumTopUp() { return BigDecimal.ONE; }
        @Override public String whatStops() { return "captchas param"; }
        @Override public Optional<BigDecimal> configuredCredit() { return Optional.ofNullable(credit); }

        @Override
        public Optional<BigDecimal> fetchBalance() {
            if (failure != null) throw failure;
            return Optional.ofNullable(balance);
        }
    }

    private ProviderBalanceMonitor monitorAt(Instant now, ProviderBalanceSource... sources) {
        var monitor = new ProviderBalanceMonitor(List.of(sources), stateRepository, costAlertService);
        monitor.useClock(Clock.fixed(now, ZoneOffset.UTC));
        return monitor;
    }

    @Test
    void firstReading_becomesTheReference_andRaisesNothing() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");

        monitorAt(T0, source).checkBalances();

        var state = storedStates.get("CAPSOLVER");
        assertEquals(0, state.getReferenceBalance().compareTo(new BigDecimal("10.00")));
        assertEquals(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC), state.getReferenceAt());
        verify(costAlertService, never()).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void consumptionBelow80PercentOfTheReference_raisesNothing() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();

        source.balance("2.01");
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        verify(costAlertService, never()).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void eightyPercentOfTheLastTopUpConsumed_raisesTheWarning_forThatTopUpCycle() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();

        source.balance("2.00");
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        var headline = ArgumentCaptor.forClass(String.class);
        verify(costAlertService).raise(eq("BALANCE:CAPSOLVER"), eq(80), eq(String.valueOf(T0.getEpochSecond())),
                headline.capture(), anyString());
        assertTrue(headline.getValue().contains("CapSolver"));
        assertTrue(headline.getValue().contains("US$ 2,00"));
        assertTrue(headline.getValue().contains("US$ 10,00"));
    }

    @Test
    void balanceExhausted_raisesThe100PercentAlert() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();

        source.balance("0.005");
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        var headline = ArgumentCaptor.forClass(String.class);
        verify(costAlertService).raise(eq("BALANCE:CAPSOLVER"), eq(100), eq(String.valueOf(T0.getEpochSecond())),
                headline.capture(), anyString());
        assertTrue(headline.getValue().contains("ESGOTADO"));
        assertTrue(headline.getValue().contains("captchas param"));
    }

    @Test
    void negativeBalance_countsAsExhausted() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();

        source.balance("-1.50");
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        verify(costAlertService).raise(eq("BALANCE:CAPSOLVER"), eq(100), anyString(), anyString(), anyString());
    }

    @Test
    void topUp_isDetectedWhenTheBalanceGoesUp_andStartsANewReferenceCycle() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();
        source.balance("1.00");
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        var topUpInstant = T0.plusSeconds(7200);
        source.balance("21.00"); // recarga de US$ 20
        monitorAt(topUpInstant, source).checkBalances();

        var state = storedStates.get("CAPSOLVER");
        assertEquals(0, state.getReferenceBalance().compareTo(new BigDecimal("21.00")));
        assertEquals(OffsetDateTime.ofInstant(topUpInstant, ZoneOffset.UTC), state.getReferenceAt());

        // 80% da NOVA referência → novo aviso, com a chave do novo ciclo (não é barrado pelo dedup antigo).
        source.balance("4.00");
        monitorAt(topUpInstant.plusSeconds(3600), source).checkBalances();

        var cycleKeys = ArgumentCaptor.forClass(String.class);
        verify(costAlertService, org.mockito.Mockito.times(2))
                .raise(eq("BALANCE:CAPSOLVER"), eq(80), cycleKeys.capture(), anyString(), anyString());
        assertNotEquals(cycleKeys.getAllValues().get(0), cycleKeys.getAllValues().get(1));
        assertEquals(String.valueOf(topUpInstant.getEpochSecond()), cycleKeys.getAllValues().get(1));
    }

    @Test
    void smallUpwardNoise_isNotATopUp() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();

        source.balance("10.40"); // estorno de tarefa
        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        assertEquals(0, storedStates.get("CAPSOLVER").getReferenceBalance().compareTo(new BigDecimal("10.00")));
    }

    @Test
    void providerWithoutBalanceApi_usesTheConfiguredCreditAsReference() {
        var source = new FakeSource("ANTHROPIC");
        source.credit = new BigDecimal("50");
        source.balance("9.00"); // crédito 50 - gasto 41 → 82% consumido

        monitorAt(T0, source).checkBalances();

        assertEquals(0, storedStates.get("ANTHROPIC").getReferenceBalance().compareTo(new BigDecimal("50")));
        verify(costAlertService).raise(eq("BALANCE:ANTHROPIC"), eq(80), anyString(), anyString(), anyString());
    }

    @Test
    void raisedAlert_triggersAnImmediateDispatch() {
        var source = new FakeSource("CAPSOLVER");
        source.balance("10.00");
        monitorAt(T0, source).checkBalances();
        source.balance("0");
        when(costAlertService.raise(anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(true);

        monitorAt(T0.plusSeconds(3600), source).checkBalances();

        verify(costAlertService).dispatchPending();
    }

    @Test
    void unconfiguredProvider_isSkippedCleanly() {
        var source = new FakeSource("TWILIO");
        source.configured = false;
        source.balance("0");

        monitorAt(T0, source).checkBalances();

        verify(stateRepository, never()).save(any());
        verify(costAlertService, never()).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void unreadableBalance_keepsTheStoredStateUntouched() {
        var source = new FakeSource("CAPSOLVER"); // balance null → Optional.empty()

        monitorAt(T0, source).checkBalances();

        verify(stateRepository, never()).save(any());
    }

    @Test
    void oneProviderFailing_doesNotStopTheOthers() {
        var broken = new FakeSource("INFOSIMPLES");
        broken.failure = new IllegalStateException("boom");
        var healthy = new FakeSource("CAPSOLVER");
        healthy.balance("0");

        monitorAt(T0, broken, healthy).checkBalances();

        verify(costAlertService).raise(eq("BALANCE:CAPSOLVER"), eq(100), anyString(), anyString(), anyString());
    }

    @Test
    void alertsDisabled_checksNothing() {
        when(costAlertService.isEnabled()).thenReturn(false);
        var source = new FakeSource("CAPSOLVER");
        source.balance("0");

        monitorAt(T0, source).checkBalances();

        verify(stateRepository, never()).findById(anyString());
    }
}
