package com.relyon.economizaai.service.sefaz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.relyon.economizaai.exception.PrefetchedUnsupportedException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefetchPolicyTest {

    // ── Generic fallback OFF (default behavior) ──────────────────────────────

    @Test
    void allowsConfiguredBlockedState() {
        var policy = new PrefetchPolicy("PE", false);

        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.PE));
    }

    @Test
    void rejectsServerFetchableStates() {
        var policy = new PrefetchPolicy("PE", false);

        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.RS));
        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.SP));
    }

    @Test
    void supportsMultipleUfsCsv() {
        var policy = new PrefetchPolicy("PE, BA", false);

        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.PE));
        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.BA));
        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.RS));
    }

    @Test
    void emptyConfigRejectsEverything() {
        var policy = new PrefetchPolicy("", false);

        assertThrows(PrefetchedUnsupportedException.class, () -> policy.requireAllowed(UnidadeFederativa.PE));
    }

    @Test
    void offMode_eligibilityEqualsTrust() {
        var policy = new PrefetchPolicy("PE", false);

        assertTrue(policy.isDeviceFetchEligible(UnidadeFederativa.PE));
        assertTrue(policy.isTrustedForIndex(UnidadeFederativa.PE));
        assertFalse(policy.isDeviceFetchEligible(UnidadeFederativa.GO));
        assertFalse(policy.isTrustedForIndex(UnidadeFederativa.GO));
    }

    // ── Generic fallback ON ──────────────────────────────────────────────────

    @Test
    void genericOn_anyUfEligibleButOnlyAllowlistTrusted() {
        var policy = new PrefetchPolicy("PE", true);

        // Eligibility widens to every UF...
        assertTrue(policy.isDeviceFetchEligible(UnidadeFederativa.PE));
        assertTrue(policy.isDeviceFetchEligible(UnidadeFederativa.GO));
        assertTrue(policy.isDeviceFetchEligible(UnidadeFederativa.SP));
        assertDoesNotThrow(() -> policy.requireAllowed(UnidadeFederativa.GO));

        // ...but index trust stays on the allowlist only.
        assertTrue(policy.isTrustedForIndex(UnidadeFederativa.PE));
        assertFalse(policy.isTrustedForIndex(UnidadeFederativa.GO));
        assertFalse(policy.isTrustedForIndex(UnidadeFederativa.SP));
    }

    @Test
    void genericOn_reportsGenericEnabled() {
        assertTrue(new PrefetchPolicy("PE", true).isGenericEnabled());
        assertFalse(new PrefetchPolicy("PE", false).isGenericEnabled());
    }

    @Test
    void trustedStatesAndTheGenericSwitchCanChangeWhileRunning() {
        var policy = new PrefetchPolicy("PE", false);

        policy.changeTrustedUfs("BA,PE");
        assertEquals("PE,BA", policy.trustedUfsCsv());
        assertTrue(policy.isTrustedForIndex(UnidadeFederativa.BA));

        policy.changeTrustedUfs("");
        assertFalse(policy.isDeviceFetchEligible(UnidadeFederativa.PE));

        policy.setGenericEnabled(true);
        assertTrue(policy.isDeviceFetchEligible(UnidadeFederativa.RS));
        assertFalse(policy.isTrustedForIndex(UnidadeFederativa.RS));
    }
}
