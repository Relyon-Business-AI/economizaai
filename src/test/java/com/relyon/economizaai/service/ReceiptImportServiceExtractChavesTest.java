package com.relyon.economizaai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Nota Fiscal Gaúcha CSV renders each chave as two space-separated blocks. */
class ReceiptImportServiceExtractChavesTest {

    private static final String ZAFFARI = "43260593015006005182651200000076311055456577";
    private static final String AMAZON = "43260915436940001177550010445204901195759810";

    @Test
    void extractsTwoBlockChavesAndIgnoresNoise() {
        var csv = """
                "","Munic.","Razão Social","Emissão","Número","TipoDoc.","Chave de Acesso","Valor","Data Registro"
                "","Nova Santa Rita","Amazon","21/09/26","44520490","Nota Fiscal Eletrônica","4326091543694000117755 0010445204901195759810","R$125,10","21/09/26"
                "","Porto Alegre","Zaffari","20/05/26","7631","Nota Fiscal de Consumidor Eletrônica","4326059301500600518265 1200000076311055456577","R$769,37","20/05/26"
                """;

        var chaves = ReceiptImportService.extractChaves(csv);

        assertThat(chaves).containsExactlyInAnyOrder(AMAZON, ZAFFARI);
    }

    @Test
    void dropsChavesWithBadCheckDigit() {
        // Last digit flipped — a valid-length but corrupt chave must not be imported.
        var corrupt = AMAZON.substring(0, 43) + (AMAZON.charAt(43) == '9' ? '8' : '9');
        var csv = "\"Chave de Acesso\"\n\"" + corrupt.substring(0, 22) + " " + corrupt.substring(22) + "\"\n";

        assertThat(ReceiptImportService.extractChaves(csv)).isEmpty();
    }
}
