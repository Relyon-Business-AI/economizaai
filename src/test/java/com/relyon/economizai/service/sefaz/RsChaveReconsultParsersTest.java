package com.relyon.economizai.service.sefaz;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-fixture tests for the RS reconsult-by-chave parsers (bulk import).
 * Fixtures captured 2026-09-22 from the live portals with the CPF masked.
 */
class RsChaveReconsultParsersTest {

    private static final String ZAFFARI_CHAVE = "43260593015006005182651200000076311055456577";
    private static final String AMAZON_CHAVE = "43260915436940001177550010445204901195759810";

    private static String fixture(String name, Charset charset) throws Exception {
        return new String(new ClassPathResource("fixtures/sefaz/rs/" + name)
                .getInputStream().readAllBytes(), charset);
    }

    @Test
    void parsesSatWebNfceZaffariWithAllItems() throws Exception {
        // SAT-WEB (legacy ASP) serves windows-1252/latin-1; SVRS serves UTF-8.
        var parsed = SatWebNfceParser.parse(
                fixture("nfce-satweb-zaffari.html", StandardCharsets.ISO_8859_1), ZAFFARI_CHAVE, "https://test/source");

        assertThat(parsed.marketName()).isEqualTo("COMPANHIA ZAFFARI COMERCIO E INDUSTRIA");
        assertThat(parsed.cnpjEmitente()).isEqualTo("93015006005182");
        assertThat(parsed.marketAddress()).contains("JUCA BATISTA");
        assertThat(parsed.issuedAt()).isEqualTo(LocalDateTime.of(2026, 5, 20, 19, 59, 1));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("769.37");
        assertThat(parsed.items()).hasSize(27);

        var first = parsed.items().get(0);
        assertThat(first.rawDescription()).isEqualTo("FILE CX/SC FGO NAT IQF 1KG");
        assertThat(first.quantity()).isEqualByComparingTo("12");
        assertThat(first.unitPrice()).isEqualByComparingTo("16.80");
        assertThat(first.totalPrice()).isEqualByComparingTo("201.60");
        // "Código" is a merchant PLU, not a GTIN — never stored as EAN.
        assertThat(first.ean()).isNull();
    }

    @Test
    void parsesNfe55AmazonWithRealEan() throws Exception {
        var parsed = SvrsNfeProdutosParser.parse(
                fixture("nfe55-consultapublica-amazon.html", StandardCharsets.UTF_8), AMAZON_CHAVE, "https://test/source");

        assertThat(parsed.marketName()).isEqualTo("AMAZON SERVICOS DE VAREJO DO BRASIL LTDA");
        assertThat(parsed.cnpjEmitente()).isEqualTo("15436940001177");
        assertThat(parsed.issuedAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 0, 26, 46));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("125.10");
        assertThat(parsed.items()).isNotEmpty();

        var coffee = parsed.items().stream()
                .filter(item -> item.rawDescription().toLowerCase().contains("orfeu"))
                .findFirst()
                .orElseThrow();
        assertThat(coffee.ean()).isEqualTo("7898912704016");
        assertThat(coffee.quantity()).isEqualByComparingTo("1");
        assertThat(coffee.unit()).isEqualTo("UN");
        assertThat(coffee.totalPrice()).isEqualByComparingTo("139.00");
        assertThat(coffee.unitPrice()).isEqualByComparingTo(new BigDecimal("139.0000"));
    }
}
