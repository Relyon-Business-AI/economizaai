package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.StateIngestionAttempt;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository;
import com.relyon.economizaai.service.ContactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StateCoverageServiceTest {

    @Mock private StateIngestionAttemptRepository repository;
    @Mock private ContactService contactService;

    private StateCoverageService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // min-failures=5, window-hours=6, max-evidence-per-uf=3 (mirrors the config defaults)
        service = new StateCoverageService(repository, contactService, 5, 6, 3);
    }

    @Test
    void hasEnoughEvidence_trueOnceExhaustedCountReachesCap() {
        when(repository.countByUfAndOutcome(UnidadeFederativa.BA, StateIngestionOutcome.EXHAUSTED))
                .thenReturn(3L);
        assertTrue(service.hasEnoughEvidence(UnidadeFederativa.BA));
    }

    @Test
    void hasEnoughEvidence_falseWhileBelowCap() {
        when(repository.countByUfAndOutcome(UnidadeFederativa.BA, StateIngestionOutcome.EXHAUSTED))
                .thenReturn(2L);
        assertFalse(service.hasEnoughEvidence(UnidadeFederativa.BA));
    }

    @Test
    void recordSuccess_firstEverForUf_alertsAdmin() {
        when(repository.existsByUfAndOutcome(UnidadeFederativa.BA, StateIngestionOutcome.SUCCESS)).thenReturn(false);

        service.recordSuccess(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL, "nfe.sefaz.ba.gov.br");

        var captor = ArgumentCaptor.forClass(StateIngestionAttempt.class);
        verify(repository).save(captor.capture());
        assertEquals(StateIngestionOutcome.SUCCESS, captor.getValue().getOutcome());
        verify(contactService).notifyAdmin(contains("BA"), contains("QR_PORTAL"));
    }

    @Test
    void recordSuccess_repeatSuccess_noAlert() {
        when(repository.existsByUfAndOutcome(UnidadeFederativa.BA, StateIngestionOutcome.SUCCESS)).thenReturn(true);

        service.recordSuccess(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL, "nfe.sefaz.ba.gov.br");

        verify(repository).save(any());
        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void reportExhausted_firstOfTheDay_alertsAdminWithEvidence() {
        when(repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(eq(UnidadeFederativa.BA), any()))
                .thenReturn(false);

        service.reportExhausted(UnidadeFederativa.BA, "29260412345678000190650010000123451123456780",
                "https://nfe.sefaz.ba.gov.br/qrcode?p=x", "QR_PORTAL: down; INFOSIMPLES: desabilitado",
                "<html>portal said no</html>");

        var captor = ArgumentCaptor.forClass(StateIngestionAttempt.class);
        verify(repository).save(captor.capture());
        assertEquals(StateIngestionOutcome.EXHAUSTED, captor.getValue().getOutcome());
        assertTrue(captor.getValue().isAdminNotified());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(contactService).notifyAdmin(contains("BA"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("29260412345678000190650010000123451123456780"));
        assertTrue(bodyCaptor.getValue().contains("portal said no"));
    }

    @Test
    void reportExhausted_alreadyNotifiedToday_recordsButStaysSilent() {
        when(repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(eq(UnidadeFederativa.BA), any()))
                .thenReturn(true);

        service.reportExhausted(UnidadeFederativa.BA, "chave", null, "summary", null);

        var captor = ArgumentCaptor.forClass(StateIngestionAttempt.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().isAdminNotified());
        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void telemetryFailure_neverPropagates() {
        when(repository.existsByUfAndOutcome(any(), any())).thenThrow(new RuntimeException("db down"));
        doThrow(new RuntimeException("db down")).when(repository).save(any());

        assertDoesNotThrow(() -> service.recordSuccess(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL, null));
        assertDoesNotThrow(() -> service.recordFailure(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL,
                StateIngestionOutcome.FETCH_FAILED, null, "x"));
        assertDoesNotThrow(() -> service.reportExhausted(UnidadeFederativa.BA, "chave", null, "summary", null));
    }

    @Test
    void report_assemblesPerUfEntriesAcrossAll27States() {
        var now = OffsetDateTime.now();
        when(repository.summarize()).thenReturn(List.of(
                summary(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL, StateIngestionOutcome.SUCCESS, 4, now),
                summary(UnidadeFederativa.BA, StateIngestionStrategy.QR_PORTAL, StateIngestionOutcome.FETCH_FAILED, 2, now.minusDays(1)),
                summary(UnidadeFederativa.BA, StateIngestionStrategy.INFOSIMPLES, StateIngestionOutcome.SUCCESS, 1, now.minusDays(2))));

        var report = service.report(Set.of(UnidadeFederativa.RS), Set.of(UnidadeFederativa.BA));

        assertEquals(UnidadeFederativa.values().length, report.states().size());
        var bahia = report.states().stream()
                .filter(entry -> entry.uf() == UnidadeFederativa.BA).findFirst().orElseThrow();
        assertEquals("EXPERIMENTAL", bahia.mode());
        assertEquals(7, bahia.attempts());
        assertEquals(5, bahia.successes());
        assertEquals(2, bahia.failures());
        assertEquals(now, bahia.lastAttemptAt());
        assertEquals(4, bahia.strategies().get("QR_PORTAL").successes());
        assertEquals(2, bahia.strategies().get("QR_PORTAL").failures());
        assertEquals(1, bahia.strategies().get("INFOSIMPLES").successes());
        var riograndedosul = report.states().stream()
                .filter(entry -> entry.uf() == UnidadeFederativa.RS).findFirst().orElseThrow();
        assertEquals("VERIFIED", riograndedosul.mode());
        assertEquals(0, riograndedosul.attempts());
        assertNull(riograndedosul.lastAttemptAt());
    }

    @Test
    void verifiedParseFailure_belowThreshold_recordsButNoAlert() {
        when(repository.countByUfAndStrategyAndOutcomeAndCreatedAtGreaterThanEqual(
                eq(UnidadeFederativa.RS), eq(StateIngestionStrategy.VERIFIED_ADAPTER),
                eq(StateIngestionOutcome.PARSE_FAILED), any())).thenReturn(3L);

        service.recordVerifiedParseFailure(UnidadeFederativa.RS, "43xx...", "https://sefaz.rs.gov.br/x",
                "receipt.parse.failed:no-items-found", "<html>new layout</html>");

        verify(repository).save(any());
        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void verifiedParseFailure_atThreshold_alertsAdminWithHtml() {
        when(repository.countByUfAndStrategyAndOutcomeAndCreatedAtGreaterThanEqual(
                eq(UnidadeFederativa.RS), eq(StateIngestionStrategy.VERIFIED_ADAPTER),
                eq(StateIngestionOutcome.PARSE_FAILED), any())).thenReturn(5L);
        when(repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(eq(UnidadeFederativa.RS), any()))
                .thenReturn(false);

        service.recordVerifiedParseFailure(UnidadeFederativa.RS, "43sample", "https://sefaz.rs.gov.br/x",
                "receipt.parse.failed:no-items-found", "<html>PORTAL CHANGED</html>");

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(contactService).notifyAdmin(contains("RS"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("PORTAL CHANGED"));
        assertTrue(bodyCaptor.getValue().contains("43sample"));
    }

    @Test
    void verifiedParseFailure_atThresholdButAlreadyAlertedToday_staysSilent() {
        when(repository.countByUfAndStrategyAndOutcomeAndCreatedAtGreaterThanEqual(
                eq(UnidadeFederativa.RS), eq(StateIngestionStrategy.VERIFIED_ADAPTER),
                eq(StateIngestionOutcome.PARSE_FAILED), any())).thenReturn(9L);
        when(repository.existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(eq(UnidadeFederativa.RS), any()))
                .thenReturn(true);

        service.recordVerifiedParseFailure(UnidadeFederativa.RS, "43sample", null,
                "receipt.parse.failed:no-items-found", "<html>x</html>");

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void verifiedParseFailure_neverPropagates() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());
        assertDoesNotThrow(() -> service.recordVerifiedParseFailure(
                UnidadeFederativa.RS, "43x", null, "reason", "<html>x</html>"));
    }

    @Test
    void hostOf_extractsHostFromUrl() {
        assertEquals("nfe.sefaz.ba.gov.br", StateCoverageService.hostOf("https://nfe.sefaz.ba.gov.br/qrcode?p=1|2"));
        assertNull(StateCoverageService.hostOf("29260412345678000190"));
        assertNull(StateCoverageService.hostOf(null));
    }

    private StateIngestionAttemptRepository.StateAttemptSummary summary(
            UnidadeFederativa uf, StateIngestionStrategy strategy, StateIngestionOutcome outcome,
            long attempts, OffsetDateTime lastAttemptAt) {
        return new StateIngestionAttemptRepository.StateAttemptSummary() {
            @Override public UnidadeFederativa getUf() { return uf; }
            @Override public StateIngestionStrategy getStrategy() { return strategy; }
            @Override public StateIngestionOutcome getOutcome() { return outcome; }
            @Override public long getAttempts() { return attempts; }
            @Override public OffsetDateTime getLastAttemptAt() { return lastAttemptAt; }
        };
    }
}
