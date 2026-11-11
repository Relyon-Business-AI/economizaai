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

    @Test
    void portalUrl_keepsHostAndAbbreviatedChaveOnly() {
        var url = "https://portalsped.fazenda.mg.gov.br/portalnfce/sistema/qrcode.xhtml"
                + "?p=31260412345678000190650010000123451123456789|2|1|1|ABCDEF0123";

        assertEquals("portalsped.fazenda.mg.gov.br chave=****6789", LogMasker.portalUrl(url));
    }

    @Test
    void portalUrl_withoutChaveOrHost() {
        assertEquals("www.sefaz.rs.gov.br chave=none", LogMasker.portalUrl("https://www.sefaz.rs.gov.br/consulta"));
        assertEquals("unknown-host chave=none", LogMasker.portalUrl("not a url at all"));
        assertNull(LogMasker.portalUrl(null));
    }

    @Test
    void rateLimitKey_masksTheEmailOfUserKeysOnly() {
        assertEquals("user:m***@example.com", LogMasker.rateLimitKey("user:maria@example.com"));
        assertEquals("ip:203.0.113.7", LogMasker.rateLimitKey("ip:203.0.113.7"));
        assertNull(LogMasker.rateLimitKey(null));
    }
}
