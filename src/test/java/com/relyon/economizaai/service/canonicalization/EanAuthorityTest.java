package com.relyon.economizaai.service.canonicalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EanAuthorityTest {

    @Test
    void realGtin13_isReal() {
        assertTrue(EanAuthority.isReal("7899516211450"));
    }

    @Test
    void realGtin8_isReal() {
        assertTrue(EanAuthority.isReal("78912345"));
    }

    @Test
    void placeholderWithSixLeadingZeros_isNotReal() {
        assertFalse(EanAuthority.isReal("00000001124676"));
        assertTrue(EanAuthority.isPlaceholder("00000001124676"));
    }

    @Test
    void allZeros_isNotReal() {
        assertFalse(EanAuthority.isReal("00000000"));
    }

    @Test
    void nullOrBlank_isNotRealAndNotPlaceholder() {
        assertFalse(EanAuthority.isReal(null));
        assertFalse(EanAuthority.isReal(""));
        assertFalse(EanAuthority.isPlaceholder(null));
        assertFalse(EanAuthority.isPlaceholder(""));
    }

    @Test
    void nonDigitCode_isNotReal() {
        assertFalse(EanAuthority.isReal("INTERNO99"));
        assertTrue(EanAuthority.isPlaceholder("INTERNO99"));
    }

    @Test
    void tooShort_isNotReal() {
        assertFalse(EanAuthority.isReal("1234"));
    }
}
