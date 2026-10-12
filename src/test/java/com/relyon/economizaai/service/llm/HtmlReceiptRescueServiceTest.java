package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HtmlReceiptRescueServiceTest {

    // UF code 43 → RS; CNPJ embedded at positions 7-20 = 12345678000190.
    private static final String CHAVE = "43260412345678000190650010000123451123456780";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SOURCE_URL = "https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=" + CHAVE;

    /** A DANFE-ish page long enough to clear the MIN_TEXT_CHARS error-shell filter. */
    private static final String HTML = """
            <html><head><script>var tracker = 'noise';</script></head><body>
            <div>MERCADO EXEMPLO LTDA - CNPJ: 12.345.678/0001-90</div>
            <div>Av. Ipiranga 1200, Porto Alegre - RS</div>
            <table><tr><td>ARROZ TIO JOAO 1KG (Codigo: 7893500020158) Qtde.: 2 UN: UN Vl. Unit.: 6.50 Vl. Total: 13.00</td></tr>
            <tr><td>LEITE ITALAC INTEGRAL 1L (Codigo: 7898080640611) Qtde.: 1 UN: UN Vl. Unit.: 5.99 Vl. Total: 5.99</td></tr></table>
            <div>Valor total R$ 18.99 Emissao: 04/10/2026 15:50:47</div>
            </body></html>""";

    private final OpenAiClient openAiClient = mock(OpenAiClient.class);
    private final PaidApiGuardService paidApiGuard = mock(PaidApiGuardService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HtmlReceiptRescueService service(boolean enabled) {
        return new HtmlReceiptRescueService(openAiClient, paidApiGuard, enabled, 80, 60000);
    }

    private void givenLlmResponds(String json) throws Exception {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(objectMapper.readTree(json));
    }

    private static String validExtraction() {
        return """
                {"is_receipt": true, "cnpj": "12.345.678/0001-90", "market_name": "MERCADO EXEMPLO LTDA",
                 "market_address": "Av. Ipiranga 1200, Porto Alegre - RS", "issued_at": "2026-10-04 15:50",
                 "total_amount": 18.99, "discount_total": null,
                 "items": [
                   {"description": "ARROZ TIO JOAO 1KG", "ean": "7893500020158", "quantity": 2,
                    "unit": "un", "unit_price": 6.50, "total_price": 13.00},
                   {"description": "LEITE ITALAC INTEGRAL 1L", "ean": "7898080640611", "quantity": 1,
                    "unit": "UN", "unit_price": 5.99, "total_price": 5.99}]}""";
    }

    @Test
    void rescue_extractsValidatedReceiptAndLedgersTheCall() throws Exception {
        givenLlmResponds(validExtraction());

        var parsed = service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        assertNotNull(parsed);
        assertEquals(CHAVE, parsed.chaveAcesso());
        assertEquals("12345678000190", parsed.cnpjEmitente());
        assertEquals("MERCADO EXEMPLO LTDA", parsed.marketName());
        assertEquals(LocalDateTime.of(2026, 10, 4, 15, 50), parsed.issuedAt());
        assertEquals(0, new BigDecimal("18.99").compareTo(parsed.totalAmount()));
        assertNull(parsed.discountTotal());
        assertEquals(SOURCE_URL, parsed.sourceUrl());
        assertEquals(HTML, parsed.rawHtml());
        assertEquals(2, parsed.items().size());

        var firstItem = parsed.items().get(0);
        assertEquals(1, firstItem.lineNumber());
        assertEquals("7893500020158", firstItem.ean());
        assertEquals("UN", firstItem.unit());
        assertEquals(0, new BigDecimal("2").compareTo(firstItem.quantity()));
        assertEquals(0, new BigDecimal("6.50").compareTo(firstItem.unitPrice()));
        assertEquals(0, new BigDecimal("13.00").compareTo(firstItem.totalPrice()));

        verify(paidApiGuard).assertUnderGlobalBudget();
        verify(paidApiGuard).assertWithinDailyCap(USER_ID, PaidApiService.LLM_HTML_RESCUE);
        verify(paidApiGuard).recordSuccess(USER_ID, PaidApiService.LLM_HTML_RESCUE, "RS", "openai");
    }

    @Test
    void rescue_sendsPlainTextNotRawMarkup() throws Exception {
        givenLlmResponds(validExtraction());

        service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        verify(openAiClient).completeJson(anyString(), argThat(userContent ->
                userContent.contains("ARROZ TIO JOAO")
                        && userContent.contains("Chave de acesso: " + CHAVE)
                        && !userContent.contains("<table>")
                        && !userContent.contains("var tracker")), anyInt());
    }

    @Test
    void rescue_disabled_returnsNullWithoutTouchingAnything() {
        var parsed = service(false).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        assertNull(parsed);
        verifyNoInteractions(openAiClient, paidApiGuard);
    }

    @Test
    void rescue_notConfigured_returnsNull() {
        when(openAiClient.isConfigured()).thenReturn(false);

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        verifyNoInteractions(paidApiGuard);
    }

    @Test
    void rescue_blankHtml_returnsNull() {
        when(openAiClient.isConfigured()).thenReturn(true);

        assertNull(service(true).rescue("  ", CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        verifyNoInteractions(paidApiGuard);
    }

    @Test
    void rescue_budgetExhausted_returnsNullWithoutCallingLlm() {
        when(openAiClient.isConfigured()).thenReturn(true);
        doThrow(new PaidApiBudgetExceededException()).when(paidApiGuard).assertUnderGlobalBudget();

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        verify(openAiClient, never()).completeJson(anyString(), anyString(), anyInt());
    }

    @Test
    void rescue_errorShellPage_returnsNullWithoutSpending() {
        when(openAiClient.isConfigured()).thenReturn(true);

        assertNull(service(true).rescue("<html><body>Erro</body></html>", CHAVE,
                UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        verify(openAiClient, never()).completeJson(anyString(), anyString(), anyInt());
        verify(paidApiGuard, never()).recordSuccess(any(), any(), any(), any());
    }

    @Test
    void rescue_llmCallFails_returnsNullAndLedgersTheFailure() {
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJson(anyString(), anyString(), anyInt()))
                .thenThrow(new LlmCallFailedException("timeout"));

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        verify(paidApiGuard).recordFailure(USER_ID, PaidApiService.LLM_HTML_RESCUE, "RS", "openai");
    }

    @Test
    void rescue_notAReceipt_returnsNull() throws Exception {
        givenLlmResponds("{\"is_receipt\": false}");

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
        // The call still cost money and must be ledgered.
        verify(paidApiGuard).recordSuccess(USER_ID, PaidApiService.LLM_HTML_RESCUE, "RS", "openai");
    }

    @Test
    void rescue_cnpjMismatch_rejectsAsHallucination() throws Exception {
        givenLlmResponds(validExtraction().replace("12.345.678/0001-90", "99.999.999/0001-99"));

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
    }

    @Test
    void rescue_totalMismatch_rejectsAsHallucination() throws Exception {
        givenLlmResponds(validExtraction().replace("18.99", "42.00"));

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
    }

    @Test
    void rescue_missingTotal_fallsBackToItemsSum() throws Exception {
        givenLlmResponds(validExtraction().replace("\"total_amount\": 18.99", "\"total_amount\": null"));

        var parsed = service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        assertNotNull(parsed);
        assertEquals(0, new BigDecimal("18.99").compareTo(parsed.totalAmount()));
    }

    @Test
    void rescue_itemWithoutAnyPrice_rejectsWholeExtraction() throws Exception {
        givenLlmResponds("""
                {"is_receipt": true, "total_amount": 18.99,
                 "items": [{"description": "ARROZ", "quantity": 2, "unit": "UN",
                            "unit_price": null, "total_price": null}]}""");

        assertNull(service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
    }

    @Test
    void rescue_derivesUnitPriceFromTotalAndInvalidEanBecomesNull() throws Exception {
        givenLlmResponds("""
                {"is_receipt": true, "total_amount": 13.00,
                 "items": [{"description": "ARROZ TIO JOAO 1KG", "ean": "cod interno 123", "quantity": 2,
                            "unit": null, "unit_price": null, "total_price": 13.00}]}""");

        var parsed = service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        assertNotNull(parsed);
        var item = parsed.items().get(0);
        assertNull(item.ean());
        assertEquals("UN", item.unit());
        assertEquals(0, new BigDecimal("6.50").compareTo(item.unitPrice()));
    }

    @Test
    void rescue_tooManyItems_rejects() throws Exception {
        var manyItems = new StringBuilder("{\"is_receipt\": true, \"total_amount\": 3.00, \"items\": [");
        for (var itemIndex = 0; itemIndex < 3; itemIndex++) {
            if (itemIndex > 0) manyItems.append(',');
            manyItems.append("{\"description\": \"ITEM ").append(itemIndex)
                    .append("\", \"quantity\": 1, \"unit\": \"UN\", \"unit_price\": 1.00, \"total_price\": 1.00}");
        }
        manyItems.append("]}");
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(objectMapper.readTree(manyItems.toString()));
        var cappedService = new HtmlReceiptRescueService(openAiClient, paidApiGuard, true, 2, 60000);

        assertNull(cappedService.rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID));
    }

    @Test
    void rescue_quantityKeepsThreeDecimalScaleForWeighedItems() throws Exception {
        givenLlmResponds("""
                {"is_receipt": true, "total_amount": 12.90,
                 "items": [{"description": "BANANA PRATA", "quantity": 1.845, "unit": "KG",
                            "unit_price": 6.99, "total_price": 12.90}]}""");

        var parsed = service(true).rescue(HTML, CHAVE, UnidadeFederativa.RS, SOURCE_URL, USER_ID);

        assertNotNull(parsed);
        assertTrue(new BigDecimal("1.845").compareTo(parsed.items().get(0).quantity()) == 0);
    }
}
