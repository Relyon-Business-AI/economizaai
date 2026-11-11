package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.dto.response.StateStatusResponse;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.StateHealthStatus;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReceiptRepository.UfFailureReasonSummary;
import com.relyon.economizaai.repository.ReceiptRepository.UfStatusSummary;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository.StateAttemptSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StateStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private StateIngestionAttemptRepository attemptRepository;

    private StateStatusService service;

    @BeforeEach
    void setUp() {
        service = new StateStatusService(sefazIngestionService, receiptRepository, attemptRepository,
                "TO", 24, 3, 5, 300);
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(sefazIngestionService.getVerifiedStates()).thenReturn(EnumSet.of(
                UnidadeFederativa.RS, UnidadeFederativa.SC, UnidadeFederativa.MG, UnidadeFederativa.TO));
        lenient().when(receiptRepository.summarizeByUfAndStatusSince(any())).thenReturn(List.of());
        lenient().when(receiptRepository.summarizeFailureReasonsSince(any())).thenReturn(List.of());
        lenient().when(attemptRepository.summarizeSince(any())).thenReturn(List.of());
    }

    private static UfStatusSummary outcome(UnidadeFederativa uf, ReceiptStatus status, long notas) {
        var summary = mock(UfStatusSummary.class);
        lenient().when(summary.getUf()).thenReturn(uf);
        lenient().when(summary.getStatus()).thenReturn(status);
        lenient().when(summary.getNotas()).thenReturn(notas);
        return summary;
    }

    private static UfFailureReasonSummary failure(UnidadeFederativa uf, String reason, long notas) {
        var summary = mock(UfFailureReasonSummary.class);
        lenient().when(summary.getUf()).thenReturn(uf);
        lenient().when(summary.getReason()).thenReturn(reason);
        lenient().when(summary.getNotas()).thenReturn(notas);
        return summary;
    }

    private static StateAttemptSummary verifiedParseFailures(UnidadeFederativa uf, long attempts) {
        var summary = mock(StateAttemptSummary.class);
        lenient().when(summary.getUf()).thenReturn(uf);
        lenient().when(summary.getStrategy()).thenReturn(StateIngestionStrategy.VERIFIED_ADAPTER);
        lenient().when(summary.getOutcome()).thenReturn(StateIngestionOutcome.PARSE_FAILED);
        lenient().when(summary.getAttempts()).thenReturn(attempts);
        return summary;
    }

    private Map<String, StateStatusResponse> statusByUf() {
        return service.currentStatus().stream()
                .collect(Collectors.toMap(StateStatusResponse::uf, Function.identity()));
    }

    @Test
    void currentStatus_listsAll27StatesOrderedByCodeWithTheirNames() {
        var states = service.currentStatus();

        assertThat(states).hasSize(27);
        assertThat(states).extracting(StateStatusResponse::uf).isSorted();
        assertThat(states).allSatisfy(state -> assertThat(state.name()).isNotBlank());
        assertThat(statusByUf().get("RS").name()).isEqualTo("Rio Grande do Sul");
    }

    @Test
    void verifiedHealthyState_isWorking_andStateWithoutVerifiedAdapter_isValidating() {
        var byUf = statusByUf();

        assertThat(byUf.get("RS").status()).isEqualTo(StateHealthStatus.WORKING);
        assertThat(byUf.get("PA").status()).isEqualTo(StateHealthStatus.VALIDATING);
    }

    @Test
    void verifiedStateOnThePausedWatchList_isUnstable() {
        assertThat(statusByUf().get("TO").status()).isEqualTo(StateHealthStatus.UNSTABLE);
    }

    @Test
    void verifiedStateWhoseRecentNotasMostlyFail_isUnstable() {
        var outcomes = List.of(
                outcome(UnidadeFederativa.SC, ReceiptStatus.CONFIRMED, 2),
                outcome(UnidadeFederativa.SC, ReceiptStatus.PORTAL_PENDING, 1),
                outcome(UnidadeFederativa.RS, ReceiptStatus.CONFIRMED, 40));
        var failures = List.of(
                failure(UnidadeFederativa.SC, "receipt.sefaz.fetch.failed:SC", 3),
                failure(UnidadeFederativa.RS, "receipt.sefaz.fetch.failed:RS", 3));
        when(receiptRepository.summarizeByUfAndStatusSince(any())).thenReturn(outcomes);
        when(receiptRepository.summarizeFailureReasonsSince(any())).thenReturn(failures);

        var byUf = statusByUf();

        assertThat(byUf.get("SC").status()).isEqualTo(StateHealthStatus.UNSTABLE);
        assertThat(byUf.get("RS").status()).as("3 failures against 40 ingested notas is healthy")
                .isEqualTo(StateHealthStatus.WORKING);
    }

    @Test
    void failuresAboutTheNotaItself_doNotMakeAStateUnstable() {
        var failures = List.of(
                failure(UnidadeFederativa.MG, "receipt.parse.failed:infosimples.not-found", 9),
                failure(UnidadeFederativa.MG, "receipt.merchant.unsupported:", 4));
        when(receiptRepository.summarizeFailureReasonsSince(any())).thenReturn(failures);

        assertThat(statusByUf().get("MG").status()).isEqualTo(StateHealthStatus.WORKING);
    }

    @Test
    void verifiedStateWithAParserFailureBurst_isUnstable() {
        var parseFailures = List.of(
                verifiedParseFailures(UnidadeFederativa.MG, 5),
                verifiedParseFailures(UnidadeFederativa.RS, 4));
        when(attemptRepository.summarizeSince(any())).thenReturn(parseFailures);

        var byUf = statusByUf();

        assertThat(byUf.get("MG").status()).isEqualTo(StateHealthStatus.UNSTABLE);
        assertThat(byUf.get("RS").status()).isEqualTo(StateHealthStatus.WORKING);
    }

    @Test
    void experimentalState_staysValidatingEvenWhenItsNotasFail() {
        var failures = List.of(failure(UnidadeFederativa.PA, "receipt.state.experimental_failed:PA", 12));
        when(receiptRepository.summarizeFailureReasonsSince(any())).thenReturn(failures);

        assertThat(statusByUf().get("PA").status()).isEqualTo(StateHealthStatus.VALIDATING);
    }

    @Test
    void currentStatus_isCachedForAFewMinutesThenRefreshed() {
        service.currentStatus();
        service.currentStatus();
        verify(receiptRepository, times(1)).summarizeByUfAndStatusSince(any());

        service.useClock(Clock.fixed(NOW.plus(Duration.ofSeconds(301)), ZoneOffset.UTC));
        service.currentStatus();

        verify(receiptRepository, times(2)).summarizeByUfAndStatusSince(any());
    }

    @Test
    void telemetryUnavailable_stillAnswersFromTheAdaptersAlone() {
        when(receiptRepository.summarizeByUfAndStatusSince(any())).thenThrow(new IllegalStateException("db down"));

        var byUf = statusByUf();

        assertThat(byUf).hasSize(27);
        assertThat(byUf.get("RS").status()).isEqualTo(StateHealthStatus.WORKING);
        assertThat(byUf.get("TO").status()).isEqualTo(StateHealthStatus.UNSTABLE);
        assertThat(byUf.get("PA").status()).isEqualTo(StateHealthStatus.VALIDATING);
    }
}
