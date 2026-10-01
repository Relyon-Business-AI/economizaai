package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.service.ContactService;
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

    @Mock private ContactService contactService;

    private InfosimplesAlertService service() {
        return new InfosimplesAlertService(contactService, new BigDecimal("5.00"));
    }

    @Test
    void consultFailureSendsOneEmailAndThrottlesTheBurst() {
        var alertService = service();

        alertService.alertConsultFailure("ce", 603, "token não tem autorização");
        alertService.alertConsultFailure("ce", 603, "token não tem autorização");
        alertService.alertConsultFailure("ms", 612, "outro erro");

        verify(contactService, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void checkLowBalanceAlertsOnlyBelowThreshold() {
        var alertService = service();

        alertService.checkLowBalance(new BigDecimal("92.08"));
        alertService.checkLowBalance(new BigDecimal("5.00"));
        verify(contactService, never()).notifyAdmin(anyString(), anyString());

        alertService.checkLowBalance(new BigDecimal("4.99"));
        verify(contactService).notifyAdmin(contains("saldo baixo"), anyString());
    }

    @Test
    void checkLowBalanceIgnoresNullSaldo() {
        service().checkLowBalance(null);

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void dispatchFailureNeverPropagates() {
        doThrow(new IllegalStateException("smtp down"))
                .when(contactService).notifyAdmin(anyString(), anyString());

        assertDoesNotThrow(() -> service().alertConsultFailure("ce", 603, "sem saldo"));
        assertDoesNotThrow(() -> service().alertAccountUnreachable());
    }
}
