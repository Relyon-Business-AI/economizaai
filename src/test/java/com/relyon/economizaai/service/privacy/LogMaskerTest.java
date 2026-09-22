package com.relyon.economizaai.service.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogMaskerTest {

    @Test
    void email_masksLocalKeepsDomain() {
        assertEquals("a***@example.com", LogMasker.email("alex@example.com"));
    }

    @Test
    void email_singleCharLocal() {
        assertEquals("a***@example.com", LogMasker.email("a@example.com"));
    }

    @Test
    void email_passesThroughNullAndBlank() {
        assertNull(LogMasker.email(null));
        assertEquals("", LogMasker.email(""));
    }

    @Test
    void email_handlesGarbage() {
        assertEquals("***", LogMasker.email("@nope.com"));
    }

    @Test
    void chave_keepsLastFour() {
        var chave = "43260412345678000190650010000123451123456780";
        assertEquals("****6780", LogMasker.chave(chave));
    }

    @Test
    void chave_passesThroughShortAndNull() {
        assertNull(LogMasker.chave(null));
        assertEquals("abc", LogMasker.chave("abc"));
    }

    @Test
    void token_keepsLastFour() {
        assertEquals("****cdef", LogMasker.token("0123456789abcdef"));
    }

    @Test
    void token_handlesShortAndNull() {
        assertNull(LogMasker.token(null));
        assertEquals("****", LogMasker.token("abc"));
    }
}
