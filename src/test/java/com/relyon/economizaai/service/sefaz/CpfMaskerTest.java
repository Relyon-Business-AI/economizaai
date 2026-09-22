package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CpfMaskerTest {

    @Test
    void strip_masksFormattedCpf() {
        var input = "Consumidor: 123.456.789-00 final";
        assertEquals("Consumidor: ***.***.***-** final", CpfMasker.strip(input));
    }

    @Test
    void strip_masksRawElevenDigitCpf() {
        var input = "CPF 12345678900 here";
        assertEquals("CPF ***.***.***-** here", CpfMasker.strip(input));
    }

    @Test
    void strip_masksUnlabeledRawElevenDigitRun() {
        var input = "consumidor 12345678900 fim";
        assertEquals("consumidor *********** fim", CpfMasker.strip(input));
    }

    @Test
    void strip_masksLooselyFormattedLabeledVariants() {
        assertEquals("CPF: ***.***.***-**", CpfMasker.strip("CPF: 123 456 789 00"));
        assertEquals("CPF ***.***.***-**", CpfMasker.strip("CPF 123.456.789 00"));
        assertEquals("cpf: ***.***.***-**,", CpfMasker.strip("cpf: 123.456.789-00,"));
    }

    @Test
    void strip_doesNotSweepUnlabeledSpacedDigitGroups() {
        // Without a CPF label nearby, spaced digit groups (totals, phone
        // numbers, codes) must survive.
        var input = "itens 123 456 789 00 unidades";
        assertEquals(input, CpfMasker.strip(input));
    }

    @Test
    void strip_doesNotMaskChaveAcesso() {
        var chave = "43250912345678000190650010000123451123456780";
        var result = CpfMasker.strip("chave " + chave);
        assertEquals("chave " + chave, result);
    }

    @Test
    void strip_doesNotMaskShortNumbers() {
        var input = "Total R$ 99,90";
        assertEquals(input, CpfMasker.strip(input));
    }

    @Test
    void strip_preservesNullAndEmpty() {
        assertNull(CpfMasker.strip(null));
        assertEquals("", CpfMasker.strip(""));
    }
}
