package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.model.CostAlert;
import com.relyon.economizaai.model.enums.CostAlertStatus;
import com.relyon.economizaai.repository.CostAlertRepository;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostAlertServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-11T15:00:00Z");
    private static final int EMAIL_FALLBACK_AFTER = 3;
    private static final int MAX_WHATSAPP_ATTEMPTS = 5;
    private static final long RETRY_INTERVAL_MS = 120_000;

    @Mock private CostAlertRepository repository;
    @Mock private OperationalAlertService operationalAlertService;

    private CostAlertService service;

    @BeforeEach
    void setUp() {
        service = newService(true);
        lenient().when(repository.save(any(CostAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CostAlertService newService(boolean enabled) {
        var created = new CostAlertService(repository, operationalAlertService, enabled,
                EMAIL_FALLBACK_AFTER, MAX_WHATSAPP_ATTEMPTS, RETRY_INTERVAL_MS);
        created.useClock(Clock.fixed(T0, ZoneOffset.UTC));
        return created;
    }

    private CostAlert pendingAlert() {
        var alert = CostAlert.builder()
                .alertKey("BUDGET:INFOSIMPLES").threshold(100).periodKey("2026-11-11")
                .headline("teto atingido").detail("detalhe")
                .status(CostAlertStatus.PENDING)
                .createdAt(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                .nextAttemptAt(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                .build();
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq(CostAlertStatus.PENDING), any()))
                .thenReturn(List.of(alert));
        return alert;
    }

    private void advance(Duration elapsed) {
        service.useClock(Clock.fixed(T0.plus(elapsed), ZoneOffset.UTC));
    }

    // ---- thresholds ----

    @Test
    void thresholdReached_below80_isZero() {
        assertEquals(0, CostAlertService.thresholdReached(new BigDecimal("79.99"), new BigDecimal("100")));
    }

    @Test
    void thresholdReached_from80_isWarning_andFrom100_isLimit() {
        assertEquals(80, CostAlertService.thresholdReached(new BigDecimal("80.00"), new BigDecimal("100")));
        assertEquals(80, CostAlertService.thresholdReached(new BigDecimal("99.99"), new BigDecimal("100")));
        assertEquals(100, CostAlertService.thresholdReached(new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(100, CostAlertService.thresholdReached(new BigDecimal("130"), new BigDecimal("100")));
    }

    @Test
    void thresholdReached_noLimit_isZero() {
        assertEquals(0, CostAlertService.thresholdReached(new BigDecimal("500"), BigDecimal.ZERO));
    }

    @Test
    void money_usesBrazilianFormat() {
        assertEquals("R$ 1.234,50", CostAlertService.money("R$", new BigDecimal("1234.5")));
    }

    // ---- dedup ----

    @Test
    void raise_firstTime_storesPendingRow() {
        when(repository.existsByAlertKeyAndThresholdAndPeriodKey("BUDGET:INFOSIMPLES", 80, "2026-11-11")).thenReturn(false);

        assertTrue(service.raise("BUDGET:INFOSIMPLES", 80, "2026-11-11", "titulo", "detalhe"));

        var captor = ArgumentCaptor.forClass(CostAlert.class);
        verify(repository).save(captor.capture());
        assertEquals(CostAlertStatus.PENDING, captor.getValue().getStatus());
        assertEquals("titulo", captor.getValue().getHeadline());
    }

    @Test
    void raise_sameServiceThresholdAndDay_isNotStoredAgain() {
        when(repository.existsByAlertKeyAndThresholdAndPeriodKey("BUDGET:INFOSIMPLES", 80, "2026-11-11")).thenReturn(true);

        assertFalse(service.raise("BUDGET:INFOSIMPLES", 80, "2026-11-11", "titulo", "detalhe"));

        verify(repository, never()).save(any());
    }

    @Test
    void raise_concurrentDuplicate_isSwallowedByTheUniqueConstraint() {
        when(repository.save(any(CostAlert.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertFalse(service.raise("BUDGET:INFOSIMPLES", 80, "2026-11-11", "titulo", "detalhe"));
    }

    @Test
    void raise_disabled_doesNothing() {
        assertFalse(newService(false).raise("BUDGET:INFOSIMPLES", 80, "2026-11-11", "titulo", "detalhe"));
        verify(repository, never()).save(any());
    }

    // ---- delivery ----

    @Test
    void dispatch_whatsAppAccepted_marksDelivered() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed("teto atingido")).thenReturn(true);

        service.dispatchPending();

        assertEquals(CostAlertStatus.DELIVERED, alert.getStatus());
        assertNotNull(alert.getWhatsappSentAt());
        verify(operationalAlertService, never()).sendEmailConfirmed(anyString(), anyString());
    }

    @Test
    void dispatch_whatsAppFails_staysPendingAndIsRetriedLater() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false);

        service.dispatchPending();

        assertEquals(CostAlertStatus.PENDING, alert.getStatus());
        assertNull(alert.getWhatsappSentAt());
        assertEquals(1, alert.getWhatsappAttempts());
        assertEquals(OffsetDateTime.ofInstant(T0.plusMillis(RETRY_INTERVAL_MS), ZoneOffset.UTC), alert.getNextAttemptAt());
        verify(operationalAlertService, never()).sendEmailConfirmed(anyString(), anyString());
    }

    @Test
    void dispatch_retriesUntilWhatsAppIsAccepted() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false, false, true);

        service.dispatchPending();
        service.dispatchPending();
        assertEquals(CostAlertStatus.PENDING, alert.getStatus());
        service.dispatchPending();

        assertEquals(CostAlertStatus.DELIVERED, alert.getStatus());
        assertEquals(2, alert.getWhatsappAttempts());
    }

    @Test
    void dispatch_afterNFailedWhatsAppAttempts_alsoSendsEmail_andKeepsTryingWhatsApp() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false);
        when(operationalAlertService.sendEmailConfirmed("teto atingido", "detalhe")).thenReturn(true);

        for (var attempt = 0; attempt < EMAIL_FALLBACK_AFTER; attempt++) {
            service.dispatchPending();
        }

        assertNotNull(alert.getEmailSentAt());
        assertEquals(CostAlertStatus.PENDING, alert.getStatus()); // WhatsApp ainda é tentado

        service.dispatchPending();
        verify(operationalAlertService, times(EMAIL_FALLBACK_AFTER + 1)).sendWhatsAppConfirmed(anyString());
        verify(operationalAlertService, times(1)).sendEmailConfirmed(anyString(), anyString()); // e-mail só uma vez
    }

    @Test
    void dispatch_whatsAppNeverAccepted_closesAsDeliveredByEmailAfterTheLastAttempt() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false);
        when(operationalAlertService.sendEmailConfirmed(anyString(), anyString())).thenReturn(true);

        for (var attempt = 0; attempt < MAX_WHATSAPP_ATTEMPTS; attempt++) {
            service.dispatchPending();
        }

        assertEquals(CostAlertStatus.DELIVERED_BY_EMAIL, alert.getStatus());
        assertEquals(MAX_WHATSAPP_ATTEMPTS, alert.getWhatsappAttempts());
    }

    @Test
    void dispatch_whatsAppAcceptedAfterTheEmailFallback_closesAsDelivered() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false, false, false, true);
        when(operationalAlertService.sendEmailConfirmed(anyString(), anyString())).thenReturn(true);

        for (var attempt = 0; attempt < 4; attempt++) {
            service.dispatchPending();
        }

        assertEquals(CostAlertStatus.DELIVERED, alert.getStatus());
        assertNotNull(alert.getEmailSentAt());
    }

    @Test
    void dispatch_emailFallbackAlsoFails_neverMarksAsSent() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed(anyString())).thenReturn(false);
        when(operationalAlertService.sendEmailConfirmed(anyString(), anyString())).thenReturn(false);

        for (var attempt = 0; attempt < MAX_WHATSAPP_ATTEMPTS + 3; attempt++) {
            service.dispatchPending();
        }

        assertEquals(CostAlertStatus.PENDING, alert.getStatus());
        assertNull(alert.getEmailSentAt());
        // WhatsApp para no máximo configurado; o e-mail continua sendo tentado.
        verify(operationalAlertService, times(MAX_WHATSAPP_ATTEMPTS)).sendWhatsAppConfirmed(anyString());
        verify(operationalAlertService, times(3 + (MAX_WHATSAPP_ATTEMPTS - EMAIL_FALLBACK_AFTER) + 1))
                .sendEmailConfirmed(anyString(), anyString());
    }

    @Test
    void dispatch_whatsAppNotConfigured_goesStraightToEmail() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(false);
        when(operationalAlertService.sendEmailConfirmed("teto atingido", "detalhe")).thenReturn(true);

        service.dispatchPending();

        assertEquals(CostAlertStatus.DELIVERED_BY_EMAIL, alert.getStatus());
        verify(operationalAlertService, never()).sendWhatsAppConfirmed(anyString());
    }

    @Test
    void dispatch_noChannelAcceptsWithinTheHorizon_expires() {
        var alert = pendingAlert();
        when(operationalAlertService.isWhatsAppReady()).thenReturn(false);
        when(operationalAlertService.sendEmailConfirmed(anyString(), anyString())).thenReturn(false);

        service.dispatchPending();
        assertEquals(CostAlertStatus.PENDING, alert.getStatus());

        advance(Duration.ofHours(25));
        service.dispatchPending();

        assertEquals(CostAlertStatus.EXPIRED, alert.getStatus());
    }

    @Test
    void dispatch_oneAlertBlowingUp_doesNotStopTheOthers() {
        var broken = CostAlert.builder().alertKey("BUDGET:CAPTCHA_SOLVE").threshold(80).periodKey("2026-11-11")
                .headline("quebra").detail("d").status(CostAlertStatus.PENDING)
                .createdAt(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC)).build();
        var healthy = CostAlert.builder().alertKey("BUDGET:INFOSIMPLES").threshold(100).periodKey("2026-11-11")
                .headline("ok").detail("d").status(CostAlertStatus.PENDING)
                .createdAt(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC)).build();
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq(CostAlertStatus.PENDING), any()))
                .thenReturn(List.of(broken, healthy));
        when(operationalAlertService.isWhatsAppReady()).thenReturn(true);
        when(operationalAlertService.sendWhatsAppConfirmed("quebra")).thenThrow(new IllegalStateException("boom"));
        when(operationalAlertService.sendWhatsAppConfirmed("ok")).thenReturn(true);

        service.dispatchPending();

        assertEquals(CostAlertStatus.PENDING, broken.getStatus());
        assertEquals(CostAlertStatus.DELIVERED, healthy.getStatus());
    }

    @Test
    void dispatch_disabled_readsNothing() {
        newService(false).dispatchPending();
        verify(repository, never()).findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any());
    }
}
