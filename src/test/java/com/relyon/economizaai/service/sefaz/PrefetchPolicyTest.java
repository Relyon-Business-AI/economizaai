package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.PrefetchedUnsupportedException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrefetchPolicyTest {

    @Test
    void allowsConfiguredBlockedState() {
        var policy = new PrefetchPolicy("PE");

        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.PE));
    }

    @Test
    void rejectsServerFetchableStates() {
        var policy = new PrefetchPolicy("PE");

        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.RS));
        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.SP));
    }

    @Test
    void supportsMultipleUfsCsv() {
        var policy = new PrefetchPolicy("PE, BA");

        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.PE));
        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.BA));
        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.RS));
    }

    @Test
    void emptyConfigRejectsEverything() {
        var policy = new PrefetchPolicy("");

        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.PE));
    }
}
