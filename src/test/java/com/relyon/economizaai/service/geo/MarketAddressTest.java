package com.relyon.economizaai.service.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketAddressTest {

    @Test
    void parsesTheSefazLayoutDroppingEmptyComplementAndBairro() {
        var address = MarketAddress.parse("AVENIDA OLSEN , 34 , , CENTRO , PENAPOLIS , SP").orElseThrow();

        assertThat(address.geocodeQueries()).containsExactly(
                "AVENIDA OLSEN 34, PENAPOLIS, SP, Brasil",
                "AVENIDA OLSEN, PENAPOLIS, SP, Brasil");
        assertThat(address.uf()).isEqualTo("SP");
    }

    @Test
    void parsesCityDashUfAsTheLastSegment() {
        var address = MarketAddress.parse("Avenida Duque de Caxias, 1130, Boa Esperanca, Abreu e Lima - PE").orElseThrow();

        assertThat(address.city()).isEqualTo("Abreu e Lima");
        assertThat(address.uf()).isEqualTo("PE");
        assertThat(address.number()).isEqualTo("1130");
    }

    @Test
    void stripsLeadingZerosAndTreatsZeroAsNoNumber() {
        assertThat(MarketAddress.parse("RUA ANTONIO BARRETO , 000186 , , UMARIZAL , BELEM , PA").orElseThrow().number())
                .isEqualTo("186");
        assertThat(MarketAddress.parse("AVENIDA FLORESTAL,555, 0, , SEGATO, ARACRUZ, ES").orElseThrow().number())
                .isEqualTo("555");
        assertThat(MarketAddress.parse("Rua Y, 0, Centro, Gaspar, SC").orElseThrow().geocodeQueries())
                .isEqualTo(List.of("Rua Y, Gaspar, SC, Brasil"));
    }

    @Test
    void cnpjOrBlankInTheAddressSlotIsMissing() {
        assertThat(MarketAddress.isMissing("79.257.291/0001-58")).isTrue();
        assertThat(MarketAddress.isMissing("  ")).isTrue();
        assertThat(MarketAddress.isMissing(null)).isTrue();
        assertThat(MarketAddress.parse("79.257.291/0001-58")).isEmpty();
    }

    @Test
    void addressWithoutUfIsNotParsed() {
        var address = "RODOVIA MARIO COVAS BR 101,  S/N  LOTE 1998 LOJAS 2009/2010";

        assertThat(MarketAddress.parse(address)).isEmpty();
        assertThat(MarketAddress.isMissing(address)).isFalse();
    }
}
