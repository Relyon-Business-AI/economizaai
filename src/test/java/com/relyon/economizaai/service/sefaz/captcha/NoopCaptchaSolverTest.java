package com.relyon.economizaai.service.sefaz.captcha;

import com.relyon.economizaai.exception.CaptchaUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoopCaptchaSolverTest {

    private final NoopCaptchaSolver solver = new NoopCaptchaSolver();

    @Test
    void isConfigured_false() {
        assertFalse(solver.isConfigured());
    }

    @Test
    void solve_throwsUnavailable() {
        assertThrows(CaptchaUnavailableException.class,
                () -> solver.solveRecaptchaV2("sitekey", "https://page"));
    }
}
