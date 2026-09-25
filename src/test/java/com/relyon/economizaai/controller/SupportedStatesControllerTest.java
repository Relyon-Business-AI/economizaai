package com.relyon.economizaai.controller;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportedStatesControllerTest {

    @Mock private SefazIngestionService sefazIngestionService;

    @Test
    void supportedStates_returnsVerifiedAndExperimentalSortedByUfCode() {
        when(sefazIngestionService.getVerifiedStates())
                .thenReturn(EnumSet.of(UnidadeFederativa.SP, UnidadeFederativa.RS, UnidadeFederativa.MG));
        when(sefazIngestionService.experimentalStates())
                .thenReturn(EnumSet.of(UnidadeFederativa.BA, UnidadeFederativa.DF));

        var body = new SupportedStatesController(sefazIngestionService).supportedStates().getBody();

        assertThat(body).isNotNull();
        assertThat(body.verified()).containsExactly("MG", "RS", "SP");
        assertThat(body.experimental()).containsExactly("BA", "DF");
    }

    @Test
    void supportedStates_emptyExperimental_returnsEmptyList() {
        when(sefazIngestionService.getVerifiedStates()).thenReturn(EnumSet.of(UnidadeFederativa.RS));
        when(sefazIngestionService.experimentalStates()).thenReturn(EnumSet.noneOf(UnidadeFederativa.class));

        var body = new SupportedStatesController(sefazIngestionService).supportedStates().getBody();

        assertThat(body.verified()).isEqualTo(List.of("RS"));
        assertThat(body.experimental()).isEmpty();
    }
}
