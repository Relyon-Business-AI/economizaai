package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PausedStateRecoveryProbeTest {

    private InfosimplesService infosimples;
    private OperationalAlertService alertService;
    private ReceiptRepository receiptRepository;
    private PausedStateRecoveryProbe probe;

    private static final String SE_CHAVE = "28261006057223040475650120002442491120227008";

    @BeforeEach
    void setUp() {
        infosimples = mock(InfosimplesService.class);
        alertService = mock(OperationalAlertService.class);
        receiptRepository = mock(ReceiptRepository.class);
        probe = new PausedStateRecoveryProbe(infosimples, alertService, receiptRepository, "SE");
        when(receiptRepository.chavesByUf(eq(UnidadeFederativa.SE), any())).thenReturn(List.of(SE_CHAVE));
    }

    @Test
    void stillDown_doesNotAlert() {
        when(infosimples.probeRouteCode(SE_CHAVE, UnidadeFederativa.SE)).thenReturn(615);

        probe.probe();

        verify(alertService, never()).alertAdmin(anyString(), anyString());
    }

    @Test
    void recovered_alertsOnceAndStopsProbing() {
        when(infosimples.probeRouteCode(SE_CHAVE, UnidadeFederativa.SE)).thenReturn(200);

        probe.probe();
        probe.probe(); // segunda rodada: já recuperada → não re-alerta nem re-sonda

        verify(alertService, times(1)).alertAdmin(contains("SE"), contains("nfce"));
        // depois de recuperar, nem consulta a chave de novo
        verify(infosimples, times(1)).probeRouteCode(SE_CHAVE, UnidadeFederativa.SE);
    }

    @Test
    void noSampleChave_skipsSilently() {
        when(receiptRepository.chavesByUf(eq(UnidadeFederativa.SE), any())).thenReturn(List.of());

        probe.probe();

        verify(infosimples, never()).probeRouteCode(anyString(), any());
        verify(alertService, never()).alertAdmin(anyString(), anyString());
    }
}
