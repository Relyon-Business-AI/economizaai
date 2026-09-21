package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canary: guards against a state silently dropping out of the fixture-verified set
 * after a change. If a dedicated adapter loses its {@code @Component}, changes its
 * {@code supportedStates()}, or its wiring breaks, the UF falls back to the
 * experimental generic path — a regression this test catches at build time.
 *
 * <p>Asserts the always-on dedicated adapters (GO/SC/MG — registered unconditionally,
 * so profile-independent). The config-gated verified states (RS/PR/SP via SVRS, MS
 * via captcha, CE via Infosimples) are each guarded by their own real-fixture tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class SefazVerifiedStatesCanaryTest {

    @Autowired
    private SefazIngestionService sefazIngestionService;

    @Test
    void dedicatedAdaptersKeepClaimingTheirStates() {
        var alwaysVerified = EnumSet.of(
                UnidadeFederativa.GO, UnidadeFederativa.SC, UnidadeFederativa.MG);
        var verified = sefazIngestionService.getVerifiedStates();
        assertTrue(verified.containsAll(alwaysVerified),
                () -> "a dedicated adapter stopped serving its state — verified=" + verified);
    }
}
