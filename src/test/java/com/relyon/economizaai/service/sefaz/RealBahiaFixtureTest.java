package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Bahia NFC-e (CRJ Produtos Naturais, Salvador, 2026-10) — DANFE devolvida
 * pelo POST de CHAVE PURA + captcha de imagem em
 * {@code NFCEC_consulta_chave_acesso.aspx} (validado ao vivo em 2026-10-21).
 * Confirma que a BA serve o layout responsivo compartilhado — o
 * {@link BahiaChaveConsultClient} só precisa do passo do antirobô na frente
 * do {@link ResponsiveDanfeParser}.
 */
class RealBahiaFixtureTest {

    private static final String CHAVE = "29260816587844000110650010004061381036664035";

    @Test
    void parseBahiaConsultaChaveReceipt_extractsItems() throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/ba/nfce-real-consulta-chave.html")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        var parsed = ResponsiveDanfeParser.parse(html, CHAVE, null);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("16587844000110", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().toUpperCase().contains("CRJ"));
        assertEquals(5, parsed.items().size());
        assertTrue(parsed.items().stream().anyMatch(item -> item.rawDescription().contains("PAPRICA")));
    }
}
