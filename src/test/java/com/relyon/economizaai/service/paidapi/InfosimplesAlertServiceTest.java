package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.service.notifications.OperationalAlertService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class InfosimplesAlertServiceTest {

    @Mock private OperationalAlertService operationalAlertService;

    private InfosimplesAlertService service() {
        return new InfosimplesAlertService(operationalAlertService, new BigDecimal("5.00"), "SE, to");
    }

    @Test
    void originUnavailableOnAKnownOfflineStateIsNotNews() {
        var alertService = service();

        alertService.alertConsultFailure("se", 615, "origem indisponível");
        alertService.alertConsultFailure("TO", 615, "origem indisponível");

        verify(operationalAlertService, never()).alertAdmin(anyString(), anyString());
    }

    @Test
    void originUnavailableOnAStateNotKnownToBeOfflineStillAlerts() {
        service().alertConsultFailure("rj", 615, "origem indisponível");

        verify(operationalAlertService).alertAdmin(contains("uf=rj"), anyString());
    }

    @Test
    void anyOtherFailureOnAKnownOfflineStateStillAlerts() {
        service().alertConsultFailure("se", 603, "sem saldo");

        verify(operationalAlertService).alertAdmin(contains("uf=se"), anyString());
    }

    @Test
    void consultFailureSendsOneEmailAndThrottlesTheBurst() {
        var alertService = service();

        alertService.alertConsultFailure("ce", 603, "token não tem autorização");
        alertService.alertConsultFailure("ce", 603, "token não tem autorização");
        alertService.alertConsultFailure("ms", 612, "outro erro");

        verify(operationalAlertService, times(1)).alertAdmin(anyString(), anyString());
    }

    @Test
    void checkLowBalanceAlertsOnlyBelowThreshold() {
        var alertService = service();

        alertService.checkLowBalance(new BigDecimal("92.08"));
        alertService.checkLowBalance(new BigDecimal("5.00"));
        verify(operationalAlertService, never()).alertAdmin(anyString(), anyString());

        alertService.checkLowBalance(new BigDecimal("4.99"));
        verify(operationalAlertService).alertAdmin(contains("saldo baixo"), anyString());
    }

    @Test
    void checkLowBalanceIgnoresNullSaldo() {
        service().checkLowBalance(null);

        verify(operationalAlertService, never()).alertAdmin(anyString(), anyString());
    }

    @Test
    void dispatchFailureNeverPropagates() {
        doThrow(new IllegalStateException("smtp down"))
                .when(operationalAlertService).alertAdmin(anyString(), anyString());

        assertDoesNotThrow(() -> service().alertConsultFailure("ce", 603, "sem saldo"));
        assertDoesNotThrow(() -> service().alertAccountUnreachable());
    }
}
