package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class InfosimplesServiceTest {

    private static final String CHAVE = "50260777863223012709650180004455861342485537";
    private static final String BASE_URL = "https://api.infosimples.com";
    private static final String API_KEY = "test-key";

    private MockRestServiceServer server;
    private InfosimplesService service;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new InfosimplesService(builder, API_KEY, BASE_URL, "MG");
    }

    @Test
    void fetchParsed_parsesAllFieldsFromJsonResponse() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(FULL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("77863223012709", parsed.cnpjEmitente());
        assertEquals("C.VALE - COOPERATIVA AGROINDUSTRIAL", parsed.marketName());
        assertEquals("RUA ANTONIO MENEGATTI FILHO , 1115 , , CENTRO , CAARAPO , MS", parsed.marketAddress());
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 30, 5), parsed.issuedAt());
        assertEquals(0, new BigDecimal("108.41").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal());
        assertNull(parsed.approxTaxFederal());
        assertNull(parsed.approxTaxEstadual());
        assertNotNull(parsed.sourceUrl());
        assertNull(parsed.rawHtml());
    }

    @Test
    void fetchParsed_parsesAllProducts() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(FULL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertEquals(2, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertEquals("BROCOLI HIBRIDO UN", first.rawDescription());
        assertNull(first.ean());
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("7.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("7.99").compareTo(first.totalPrice()));

        var second = parsed.items().get(1);
        assertEquals(2, second.lineNumber());
        assertEquals("ABOBORA CABOTIA PICADA KG", second.rawDescription());
        assertEquals(0, new BigDecimal("0.377").compareTo(second.quantity()));
        assertEquals("KG", second.unit());
        assertEquals(0, new BigDecimal("7.49").compareTo(second.unitPrice()));
        assertEquals(0, new BigDecimal("2.82").compareTo(second.totalPrice()));
    }

    @Test
    void fetchParsed_usesUfCodeLowercasedInUrl() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/rs/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(MINIMAL_RESPONSE_JSON, MediaType.APPLICATION_JSON));

        service.fetchParsed(CHAVE, UnidadeFederativa.RS);

        server.verify();
    }

    @Test
    void fetchParsed_throwsOnNon200Code() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess("{\"code\":404,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(ReceiptParseException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_throwsOnEmptyDataArray() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess("{\"code\":200,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(ReceiptParseException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_throwsOnHttpError() {
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withServerError());

        assertThrows(RuntimeException.class,
                () -> service.fetchParsed(CHAVE, UnidadeFederativa.MS));
    }

    @Test
    void fetchParsed_handlesNullEmitente() {
        var json = "{\"code\":200,\"data\":[{\"emitente\":null,\"informacoes_nota\":null,"
                + "\"normalizado_valor_a_pagar\":10.00,\"normalizado_valor_desconto\":null,"
                + "\"produtos\":[],\"site_receipt\":null}]}";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertNull(parsed.cnpjEmitente());
        assertNull(parsed.marketName());
        assertNull(parsed.issuedAt());
        assertEquals(0, new BigDecimal("10.00").compareTo(parsed.totalAmount()));
        assertEquals(0, parsed.items().size());
    }

    @Test
    void fetchParsed_handlesNullOrMalformedDate() {
        var json = "{\"code\":200,\"data\":[{\"emitente\":null,"
                + "\"informacoes_nota\":{\"data_emissao\":\"invalid\",\"hora_emissao\":\"99:99:99\"},"
                + "\"normalizado_valor_a_pagar\":5.00,\"normalizado_valor_desconto\":null,"
                + "\"produtos\":[],\"site_receipt\":null}]}";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ms/nfce?token=test-key&nfce=" + CHAVE))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(CHAVE, UnidadeFederativa.MS);

        assertNull(parsed.issuedAt());
    }

    // ── Real cross-shape fixtures: Infosimples returns a "resumida" schema for
    //    some states (PR) and a fuller "completa" schema for others (SP). The
    //    mapping must read both. ─────────────────────────────────────────────────

    @Test
    void fetchParsed_mapsCompletaShape_saoPaulo() {
        var spChave = "35260716881767001421650010000620781001241617";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/sp/nfce?token=test-key&nfce=" + spChave))
                .andRespond(withSuccess(fixture("sp-completa"), MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(spChave, UnidadeFederativa.SP);

        assertEquals("16881767001421", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("MERCADAO ATACADISTA"));
        // total lives under totais.normalizado_valor_nfe in the completa shape
        assertEquals(0, new BigDecimal("158.12").compareTo(parsed.totalAmount()));
        // single-field nfe.data_emissao with a tz offset that must be stripped
        assertEquals(LocalDateTime.of(2026, 7, 5, 14, 11, 14), parsed.issuedAt());
        assertEquals(20, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals("LARANJA PERA KG", first.rawDescription());  // descricao, not nome
        assertEquals(0, new BigDecimal("0.94").compareTo(first.quantity())); // qtd, not normalizado_quantidade
        assertEquals("KG", first.unit());
        assertEquals(0, new BigDecimal("2.80").compareTo(first.totalPrice())); // normalizado_valor

        var itemSum = parsed.items().stream()
                .map(item -> item.totalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }

    @Test
    void fetchParsed_mapsResumidaShape_parana() {
        var prChave = "41260361585865261893650030000564031777660148";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/pr/nfce?token=test-key&nfce=" + prChave))
                .andRespond(withSuccess(fixture("pr-resumida"), MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(prChave, UnidadeFederativa.PR);

        assertEquals("61585865261893", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("RAIADROGASIL"));
        assertEquals(0, new BigDecimal("28.97").compareTo(parsed.totalAmount()));
        assertEquals(3, parsed.items().size());

        var first = parsed.items().get(0);
        assertTrue(first.rawDescription().contains("PAPIN"));  // nome
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));
        assertEquals("UN", first.unit());
        assertEquals(0, new BigDecimal("9.99").compareTo(first.totalPrice()));
    }

    @Test
    void fetchParsed_mapsMgResumidaShape_viaResumidaEndpoint() {
        // MG lives at the `.../mg/nfce-resumida` slug (the default `/nfce` returns
        // code 602) and returns a distinct schema: produtos_servicos / valores /
        // emitente.razao_social / nfce.data_emissao.
        var mgChave = "31260911614938000118650180003378659268089479";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/mg/nfce-resumida?token=test-key&nfce=" + mgChave))
                .andRespond(withSuccess(fixture("mg-nfce-resumida"), MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(mgChave, UnidadeFederativa.MG);

        assertEquals("11614938000118", parsed.cnpjEmitente());
        assertTrue(parsed.marketName().contains("SUPERMERCADO BORGES E MIRANDA"));
        // total lives under valores.normalizado_valor_total_servico
        assertEquals(0, new BigDecimal("73.45").compareTo(parsed.totalAmount()));
        // emission datetime lives under the nfce block
        assertEquals(LocalDateTime.of(2026, 9, 21, 6, 35, 19), parsed.issuedAt());
        assertEquals(6, parsed.items().size());

        var first = parsed.items().get(0);
        assertEquals(1, first.lineNumber());
        assertTrue(first.rawDescription().contains("GEL KANECHOM"));  // descricao
        assertNull(first.ean(), "MG resumida carries no GTIN");
        assertEquals(0, BigDecimal.ONE.compareTo(first.quantity()));  // quantidade
        assertEquals("UN", first.unit());                             // unidade_comercial
        assertEquals(0, new BigDecimal("9.99").compareTo(first.unitPrice()));
        assertEquals(0, new BigDecimal("9.99").compareTo(first.totalPrice())); // normalizado_valor

        var itemSum = parsed.items().stream()
                .map(item -> item.totalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, itemSum.compareTo(parsed.totalAmount()));
    }

    @Test
    void fetchParsed_mapsCearaReceipt() {
        var ceChave = "23260723301562000170650110000433671111560131";
        server.expect(requestTo(BASE_URL + "/api/v2/consultas/sefaz/ce/nfce?token=test-key&nfce=" + ceChave))
                .andRespond(withSuccess(fixture("ce-nfce"), MediaType.APPLICATION_JSON));

        var parsed = service.fetchParsed(ceChave, UnidadeFederativa.CE);

        assertTrue(parsed.marketName().contains("FRIOS"));
        assertEquals(0, new BigDecimal("196.17").compareTo(parsed.totalAmount()));
        assertEquals(26, parsed.items().size());

        var first = parsed.items().get(0);
        assertTrue(first.rawDescription().contains("PIMENTA DE CHEIRO"));
        assertNull(first.ean(), "loose produce is 'SEM GTIN' — no barcode");

        // Packaged items carry a real EAN, normalized to the catalog's 13-digit form.
        var neston = parsed.items().stream()
                .filter(item -> item.rawDescription().contains("NESTON")).findFirst().orElseThrow();
        assertEquals("7891000098950", neston.ean());
    }

    @Test
    void extractGtin_normalizesToEan13AndDropsNonBarcodes() {
        assertEquals("7891000098950", InfosimplesService.extractGtin("7891000098950"));      // already 13
        assertEquals("7891000098950", InfosimplesService.extractGtin("07891000098950"));     // 14, pad zero dropped
        assertNull(InfosimplesService.extractGtin("SEM GTIN"));                                // not a barcode
        assertNull(InfosimplesService.extractGtin((String) null));
        assertNull(InfosimplesService.extractGtin("9669"));                                   // internal PLU, too short
        assertEquals("7891000098950",
                InfosimplesService.extractGtin("SEM GTIN", "07891000098950"));                // first valid wins
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/infosimples/" + name + ".json")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ── JSON fixtures ──────────────────────────────────────────────────────────

    private static final String FULL_RESPONSE_JSON = """
            {
              "code": 200,
              "data": [{
                "emitente": {
                  "cnpj": "77.863.223/0127-09",
                  "endereco": "RUA ANTONIO MENEGATTI FILHO , 1115 , , CENTRO , CAARAPO , MS",
                  "nome_razao_social": "C.VALE - COOPERATIVA AGROINDUSTRIAL"
                },
                "informacoes_nota": {
                  "data_emissao": "01/07/2026",
                  "hora_emissao": "08:30:05"
                },
                "normalizado_valor_a_pagar": 108.41,
                "normalizado_valor_desconto": null,
                "produtos": [
                  {
                    "nome": "BROCOLI HIBRIDO UN",
                    "normalizado_quantidade": 1.0,
                    "normalizado_valor_unitario": 7.99,
                    "normalizado_valor_total_produto": 7.99,
                    "unidade": "UN"
                  },
                  {
                    "nome": "ABOBORA CABOTIA PICADA KG",
                    "normalizado_quantidade": 0.377,
                    "normalizado_valor_unitario": 7.49,
                    "normalizado_valor_total_produto": 2.82,
                    "unidade": "KG"
                  }
                ],
                "site_receipt": "https://example.com/cached.html"
              }]
            }
            """;

    private static final String MINIMAL_RESPONSE_JSON = """
            {
              "code": 200,
              "data": [{
                "emitente": {"cnpj": "12345678000190", "endereco": null, "nome_razao_social": "X"},
                "informacoes_nota": {"data_emissao": "01/07/2026", "hora_emissao": "10:00:00"},
                "normalizado_valor_a_pagar": 1.00,
                "normalizado_valor_desconto": null,
                "produtos": [],
                "site_receipt": null
              }]
            }
            """;
}
