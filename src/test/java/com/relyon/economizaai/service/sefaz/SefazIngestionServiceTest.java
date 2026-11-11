package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.notifications.OperationalAlertService;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import com.relyon.economizaai.exception.ExperimentalCaptchaWallException;
import com.relyon.economizaai.exception.ExperimentalStateFailedException;
import com.relyon.economizaai.exception.InfosimplesNoDataException;
import com.relyon.economizaai.exception.InfosimplesProviderException;
import com.relyon.economizaai.exception.InvalidQrPayloadException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.exception.SefazPortalRejectionException;
import com.relyon.economizaai.exception.UnsupportedStateException;
import com.relyon.economizaai.model.enums.InfosimplesOutcome;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.llm.HtmlReceiptRescueService;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolveTracker;
import com.relyon.economizaai.service.sefaz.captcha.CaptchaSolver;
import com.relyon.economizaai.service.sefaz.SefazIngestionService.FetchedDocument;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SefazIngestionServiceTest {

    // UF code 43 → RS.
    private static final String CHAVE_RS = "43260412345678000190650010000123451123456782";
    /** The nota's own page (prints its chave, grouped in fours) in a layout the parser can't read. */
    private static final String CHANGED_LAYOUT_RS = "<html><body><div class=\"novo-layout\">Chave de acesso: "
            + "4326 0412 3456 7800 0190 6500 1000 0123 4511 2345 6782</div></body></html>";
    // UF code 42 → SC.
    private static final String CHAVE_SC = "42260650552333000100650080001188891101255904";
    // UF code 35 → SP.
    private static final String CHAVE_SP = "35260412345678000190650010000123451123456780";
    // UF code 26 → PE (real nota that failed for carlabarbosatk, 2026-09-19).
    private static final String CHAVE_PE = "26260920534381000287651020001738551001743421";

    /** Metering is verified separately in PaidApiGuardServiceTest; here it's a no-op collaborator. */
    private final PaidApiGuardService paidApiGuard = mock(PaidApiGuardService.class);
    /** Telemetry is verified in the experimental tests below and in StateCoverageServiceTest. */
    private final StateCoverageService stateCoverage = mock(StateCoverageService.class);
    /** The free RS bare-chave arbiter for portal rejection pages (broken printed QR). */
    private final RsChaveReconsultService rsChaveReconsult = mock(RsChaveReconsultService.class);
    /** Reconsulta por chave pura (RS/CE/BA) — a cadeia de fallback do submit. */
    private final ChaveReconsultService chaveReconsult = mock(ChaveReconsultService.class);
    /** Alerta admin (WhatsApp/e-mail) quando uma chave manual não tem caminho. */
    private final OperationalAlertService operationalAlert = mock(OperationalAlertService.class);
    /** Real policy with the production default (PE) so the device-fetch exemption is exercised. */
    private final PrefetchPolicy prefetchPolicy = new PrefetchPolicy("PE", false);
    /** The LLM parse-rescue layer; the mock's default null = "didn't rescue", so the chain continues. */
    private final HtmlReceiptRescueService htmlReceiptRescue = mock(HtmlReceiptRescueService.class);

    /** A simple in-test adapter so the constructor's duplicate guard can be exercised. */
    private static class FakeAdapter implements SefazAdapter {
        private final Set<UnidadeFederativa> states;

        FakeAdapter(Set<UnidadeFederativa> states) {
            this.states = states;
        }

        @Override
        public Set<UnidadeFederativa> supportedStates() {
            return states;
        }

        @Override
        public String fetchHtml(String qrPayload) {
            return "<html>fake</html>";
        }

        @Override
        public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
            return null;
        }
    }

    private SefazAdapter mockAdapterFor(UnidadeFederativa... ufs) {
        var adapter = mock(SefazAdapter.class);
        when(adapter.supportedStates()).thenReturn(Set.of(ufs));
        return adapter;
    }

    private ParsedReceipt sampleParsed() {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE_RS)
                .cnpjEmitente("12345678000190")
                .marketName("ZAFFARI")
                .issuedAt(LocalDateTime.now())
                .totalAmount(new BigDecimal("99.48"))
                .rawHtml("<html>raw</html>")
                .items(List.of())
                .build();
    }

    @Test
    void fromClientContent_acceptsContentCarryingTheChave() {
        var service = new SefazIngestionService(
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.PE))), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var content = "<nfeProc><NFe><infNFe Id=\"NFe" + CHAVE_PE + "\"><det/></infNFe></NFe></nfeProc>";

        var fetched = service.fromClientContent(content, CHAVE_PE, UnidadeFederativa.PE, "http://nfce.sefaz.pe.gov.br/x");

        assertEquals(CHAVE_PE, fetched.chave());
        assertEquals(UnidadeFederativa.PE, fetched.uf());
        assertTrue(fetched.html().contains(CHAVE_PE));
        assertNull(fetched.preParsed(), "client content is parsed by the adapter, not pre-parsed");
    }

    @Test
    void fromClientContent_rejectsContentForADifferentNota() {
        var service = new SefazIngestionService(
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.PE))), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        // A client must not staple another nota's page onto this chave.
        var otherNota = "<nfeProc><infNFe Id=\"NFe35260412345678000190650010000123451123456780\"/></nfeProc>";

        assertThrows(ReceiptParseException.class,
                () -> service.fromClientContent(otherNota, CHAVE_PE, UnidadeFederativa.PE, null));
    }

    @Test
    void clientContent_parsesRealPeNotaEndToEnd() throws Exception {
        // The gap-fill GenericQrPortalAdapter claims PE; client content routes through
        // the same parseHtml -> NfceXmlParser as a server-side fetch would.
        var generic = new GenericQrPortalAdapter(RestClient.builder(), mock(CaptchaSolver.class),
                1000, "test", true, 1, 0, "gov.br");
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var xml = new String(new ClassPathResource("fixtures/sefaz/pe/pe-novo-israel-4items.xml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        var fetched = service.fromClientContent(xml, CHAVE_PE, UnidadeFederativa.PE, "http://nfce.sefaz.pe.gov.br/x");
        var parsed = service.parse(fetched, null);

        assertEquals(4, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("24.13")));
        assertTrue(parsed.marketName().contains("NOVO ISRAEL"));
    }

    @Test
    void fromClientContent_rejectsUnsupportedUf() {
        var service = new SefazIngestionService(
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.RS))), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(UnsupportedStateException.class,
                () -> service.fromClientContent("body " + CHAVE_PE, CHAVE_PE, UnidadeFederativa.PE, null));
    }

    @Test
    void constructor_registersAllSupportedStates() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS, UnidadeFederativa.SC);
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);

        var service = new SefazIngestionService(List.of(rsAdapter, spAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        // RS routes to the RS adapter via fetch.
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>rs</html>");
        var fetched = service.fetch(CHAVE_RS, null);
        assertSame(rsAdapter, fetched.adapter());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
    }

    @Test
    void constructor_throwsWhenTwoAdaptersClaimSameUf() {
        var first = new FakeAdapter(Set.of(UnidadeFederativa.RS));
        var second = new FakeAdapter(Set.of(UnidadeFederativa.RS, UnidadeFederativa.SC));
        List<SefazAdapter> adapters = List.of(first, second);

        var error = assertThrows(IllegalStateException.class,
                () -> new SefazIngestionService(adapters, Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue));
        assertTrue(error.getMessage().contains("RS"));
    }

    @Test
    void constructor_sameAdapterInstanceListedTwiceIsNotADuplicate() {
        var shared = mockAdapterFor(UnidadeFederativa.RS);

        // Same instance reappearing must not trip the guard (prior == adapter).
        var service = new SefazIngestionService(List.of(shared, shared), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        when(shared.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var fetched = service.fetch(CHAVE_RS, null);
        assertSame(shared, fetched.adapter());
    }

    @Test
    void fetch_picksAdapterByUfSanitizesCpfAndSetsSourceUrlForHttp() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var httpPayload = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        when(rsAdapter.fetchHtml(httpPayload))
                .thenReturn("<html>cliente 123.456.789-00 fim</html>");
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(httpPayload, null);

        assertSame(rsAdapter, fetched.adapter());
        assertEquals(CHAVE_RS, fetched.chave());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
        assertEquals(httpPayload, fetched.sourceUrl());
        assertTrue(fetched.html().contains("***.***.***-**"));
        assertTrue(!fetched.html().contains("123.456.789-00"));
    }

    @Test
    void fetch_budgetExhausted_stillScrapesACaptchaFreePortal() {
        // The daily paid budget is gone, but an RS scrape buys nothing — it must go through.
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var httpPayload = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        when(rsAdapter.fetchHtml(httpPayload)).thenReturn("<html>ok</html>");
        when(paidApiGuard.isDailyBudgetExhausted(PaidApiService.CAPTCHA_SOLVE)).thenReturn(true);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(httpPayload, null);

        assertSame(rsAdapter, fetched.adapter());
    }

    @Test
    void fetch_budgetExhausted_captchaPortalFailsWithTheBudgetReason() {
        // The solver refuses to buy a token while the budget is exhausted; the user must be
        // told about the daily limit, not about a captcha failure.
        var captchaAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(captchaAdapter.fetchHtml(anyString())).thenAnswer(invocation -> {
            CaptchaSolveTracker.assertSpendAllowed();
            return "<html>ok</html>";
        });
        when(paidApiGuard.isDailyBudgetExhausted(PaidApiService.CAPTCHA_SOLVE)).thenReturn(true);
        var service = new SefazIngestionService(List.of(captchaAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(PaidApiBudgetExceededException.class,
                () -> service.fetch("https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS, null));
        CaptchaSolveTracker.blockSpend(false);
    }

    @Test
    void fetch_setsNullSourceUrlForNonHttpPayload() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(CHAVE_RS, null);

        assertNull(fetched.sourceUrl());
    }

    @Test
    void fetch_usesAdapterPreflightWhenSecurityUrlDoesNotExposeChave() {
        var scAdapter = mockAdapterFor(UnidadeFederativa.SC);
        var securityUrl = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc";
        when(scAdapter.preflightChave(securityUrl)).thenReturn(Optional.of(CHAVE_SC));
        when(scAdapter.fetchHtml(securityUrl)).thenReturn("<html>sc</html>");
        var service = new SefazIngestionService(List.of(scAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(securityUrl, null);

        assertSame(scAdapter, fetched.adapter());
        assertEquals(CHAVE_SC, fetched.chave());
        assertEquals(UnidadeFederativa.SC, fetched.uf());
        assertEquals(securityUrl, fetched.sourceUrl());
    }

    @Test
    void fetch_throwsUnsupportedStateWhenNoAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(UnsupportedStateException.class, () -> service.fetch(CHAVE_SP, null));
    }

    @Test
    void fetch_throwsInvalidQrPayloadForGarbageInput() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(InvalidQrPayloadException.class, () -> service.fetch("not-a-chave", null));
    }

    @Test
    void parse_delegatesToFetchedAdapter() {
        var rsAdapter = mock(SefazAdapter.class);
        var fetched = new FetchedDocument(rsAdapter, "<html>x</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://example/src");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>x</html>", CHAVE_RS, "https://example/src"))
                .thenReturn(expected);

        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var parsed = service.parse(fetched, null);

        assertSame(expected, parsed);
    }

    @Test
    void fetchThenParse_yieldsAdapterResult() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>raw</html>");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml(eq("<html>raw</html>"), eq(CHAVE_RS), any()))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var parsed = service.parse(service.fetch(CHAVE_RS, null), null);

        assertSame(expected, parsed);
        verify(rsAdapter).fetchHtml(CHAVE_RS);
    }

    @Test
    void reparseStored_usesAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>stored</html>", CHAVE_RS, "src"))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var parsed = service.reparseStored(UnidadeFederativa.RS, "<html>stored</html>", CHAVE_RS, "src");

        assertSame(expected, parsed);
    }

    @Test
    void reparseStored_throwsUnsupportedStateWhenNoAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(UnsupportedStateException.class,
                () -> service.reparseStored(UnidadeFederativa.SP, "<html>x</html>", CHAVE_SP, null));
    }

    // ── Infosimples fallback tests ─────────────────────────────────────────────

    @Test
    void fetch_fallsBackToInfosimplesWhenPrimaryExhaustsRetries() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_RS), eq(UnidadeFederativa.RS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(CHAVE_RS, null);

        assertNull(fetched.adapter());
        assertNull(fetched.html());
        assertSame(preParsed, fetched.preParsed());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
        verify(infosimples).fetchParsed(CHAVE_RS, UnidadeFederativa.RS);
    }

    @Test
    void fetch_fallsBackWhenCaptchaUnavailable() {
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenThrow(new CaptchaUnavailableException("MS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        var chaveMs = "50260777863223012709650180004455861342485537";
        when(infosimples.fetchParsed(eq(chaveMs), eq(UnidadeFederativa.MS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(chaveMs, null);

        assertSame(preParsed, fetched.preParsed());
    }

    @Test
    void fetch_doesNotSpendInfosimplesOnDeterministicParseFailure() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var deterministicEx = new ReceiptParseException("captcha-sitekey-missing");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(deterministicEx);
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS, null));

        assertSame(deterministicEx, thrown);
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_bareChaveWithSignatureRequiringAdapter_routesToInfosimplesWithoutScraping() {
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(spAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(CHAVE_SP, null); // bare chave, no QR signature

        assertSame(preParsed, fetched.preParsed());
        assertNull(fetched.adapter());
        verify(infosimples).fetchParsed(CHAVE_SP, UnidadeFederativa.SP);
        // the doomed scrape is skipped entirely
        verify(spAdapter, never()).fetchHtml(anyString());
    }

    @Test
    void fetch_bareChaveWithSignatureRequiringAdapter_noInfosimples_throwsWithoutScraping() {
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(spAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_SP, null));
        verify(spAdapter, never()).fetchHtml(anyString());
    }

    @Test
    void fetch_bareChaveWithNativeAdapter_scrapesAndDoesNotUseInfosimples() {
        // MS/SC consult by chave natively (requiresQrSignature defaults false),
        // so a bare chave must go through the adapter, not the paid fallback.
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        var chaveMs = "50260777863223012709650180004455861342485537";
        when(msAdapter.fetchHtml(chaveMs)).thenReturn("<html>ms</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(chaveMs, null); // bare chave

        assertSame(msAdapter, fetched.adapter());
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_realQrUrlWithSignatureRequiringAdapter_scrapesNotInfosimples() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.requiresQrSignature()).thenReturn(true);
        var httpUrl = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1|1|hash";
        when(rsAdapter.fetchHtml(httpUrl)).thenReturn("<html>rs</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(httpUrl, null); // full signed URL — scrapeable

        assertSame(rsAdapter, fetched.adapter());
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void requireSupported_passesForRegisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.requireSupported(UnidadeFederativa.RS);
    }

    @Test
    void requireSupported_throwsForUnregisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(UnsupportedStateException.class,
                () -> service.requireSupported(UnidadeFederativa.SP));
    }

    @Test
    void fetch_doesNotCallInfosimplesWhenPrimarySucceeds() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetch(CHAVE_RS, null);

        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_rethrowsPrimaryExceptionWhenInfosimplesDisabled() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var primaryEx = new SefazFetchException("RS");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(primaryEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_RS, null));

        assertSame(primaryEx, thrown);
    }

    @Test
    void fetch_propagatesInfosimplesExceptionWhenBothFail() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(any(), any())).thenThrow(new ReceiptParseException("infosimples.error"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS, null));
    }

    @Test
    void parse_returnsPreParsedReceiptDirectly() {
        var preParsed = sampleParsed();
        var fetched = new FetchedDocument(null, null, CHAVE_RS, UnidadeFederativa.RS, null, preParsed);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var result = service.parse(fetched, null);

        assertSame(preParsed, result);
    }

    @Test
    void parse_delegatesToAdapterWhenNoPreParsed() {
        var rsAdapter = mock(SefazAdapter.class);
        var fetched = new FetchedDocument(rsAdapter, "<html>x</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://example/src");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>x</html>", CHAVE_RS, "https://example/src"))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var parsed = service.parse(fetched, null);

        assertSame(expected, parsed);
        verify(rsAdapter).parseHtml("<html>x</html>", CHAVE_RS, "https://example/src");
    }

    @Test
    void fetch_fallbackWorksForAnyUf() {
        // UF code 50 → MS
        var chaveMs = "50260777863223012709650180004455861342485537";
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("MS"));
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(chaveMs), eq(UnidadeFederativa.MS))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(chaveMs, null);

        assertSame(preParsed, fetched.preParsed());
        assertEquals(UnidadeFederativa.MS, fetched.uf());
    }

    // ── Experimental fallback chain (states without a dedicated adapter) ──────

    // UF code 29 → BA (no dedicated adapter — served by the experimental chain).
    private static final String CHAVE_BA = "29260412345678000190650010000123451123456783";
    private static final String QR_URL_BA = "https://nfe.sefaz.ba.gov.br/servicos/nfce/qrcode.aspx?p="
            + CHAVE_BA + "|2|1|1|deadbeef";

    private static final CaptchaSolver NO_CAPTCHA = new CaptchaSolver() {
        @Override public boolean isConfigured() { return false; }
        @Override public String solveRecaptchaV2(String siteKey, String pageUrl) {
            throw new UnsupportedOperationException("no captcha in tests");
        }
    };

    /** Real generic adapter (so instanceof-based dispatch applies) with a stubbed HTTP layer. */
    private GenericQrPortalAdapter genericAdapter(String htmlOrNull) {
        return new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", true, 1, 0, "gov.br") {
            @Override
            protected PortalFetch httpFetch(String url) {
                if (htmlOrNull == null) throw new RestClientException("portal down");
                return new PortalFetch(htmlOrNull, url, null);
            }
        };
    }

    @Test
    void constructor_gapFillsUnclaimedUfsWithGenericAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var generic = genericAdapter("<html>ba</html>");
        var service = new SefazIngestionService(List.of(rsAdapter, generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(QR_URL_BA, null);

        assertSame(generic, fetched.adapter());
        assertEquals(UnidadeFederativa.BA, fetched.uf());
        assertTrue(service.getVerifiedStates().contains(UnidadeFederativa.RS));
        assertTrue(service.experimentalStates().contains(UnidadeFederativa.BA));
        assertTrue(!service.experimentalStates().contains(UnidadeFederativa.RS));
    }

    @Test
    void fetchViaInfosimplesFallback_experimentalUf_recordsLayerSuccessForCoverage() {
        // The device-fetch sweeper rescues experimental UFs (PA/RN) through this entry point;
        // without recording it the coverage view showed 0% and the spend cap never saw a success.
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS), genericAdapter(null)),
                Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult,
                operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetchViaInfosimplesFallback(CHAVE_BA, UnidadeFederativa.BA, null);

        verify(stateCoverage).recordSuccess(UnidadeFederativa.BA, StateIngestionStrategy.INFOSIMPLES, null);
    }

    @Test
    void fetchViaInfosimplesFallback_verifiedUf_recordsNoCoverage() {
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP))).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.SP)), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetchViaInfosimplesFallback(CHAVE_SP, UnidadeFederativa.SP, null);

        verify(stateCoverage, never()).recordSuccess(any(), any(), any());
    }

    @Test
    void parse_clientContentOnExperimentalUf_recordsDeviceFetchLayer() {
        var expected = sampleParsed();
        var generic = new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", true, 1, 0, "gov.br") {
            @Override
            public ParsedReceipt parseHtml(String html, String chave, String sourceUrl) {
                return expected;
            }
        };
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS), generic), Optional.empty(),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = service.fromClientContent("<html>" + CHAVE_BA + "</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        assertSame(expected, service.parse(fetched, null));

        verify(stateCoverage).recordSuccess(UnidadeFederativa.BA, StateIngestionStrategy.DEVICE_FETCH, "nfe.sefaz.ba.gov.br");
        verify(stateCoverage, never()).recordSuccess(any(), eq(StateIngestionStrategy.QR_PORTAL), any());
    }

    @Test
    void constructor_disabledGenericAdapterLeavesUfsUnsupported() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var disabledGeneric = new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", false, 1, 0, "gov.br");
        var service = new SefazIngestionService(List.of(rsAdapter, disabledGeneric), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(UnsupportedStateException.class, () -> service.requireSupported(UnidadeFederativa.BA));
    }

    @Test
    void parse_verifiedStateParseFailure_recordsRegressionAndRethrows() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var parseEx = new ReceiptParseException("no-items-found");
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(parseEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, CHANGED_LAYOUT_RS, CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var thrown = assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        assertSame(parseEx, thrown);
        verify(stateCoverage).recordVerifiedParseFailure(eq(UnidadeFederativa.RS), eq(CHAVE_RS),
                eq("https://sefaz.rs.gov.br/x"), anyString(), eq(CHANGED_LAYOUT_RS));
    }

    @Test
    void parse_verifiedStateOutagePage_isNotAParserRegression() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var parseEx = new ReceiptParseException("no-items-found");
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(parseEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, "<html><body>Portal em manutenção</body></html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var thrown = assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        assertSame(parseEx, thrown);
        verify(stateCoverage, never()).recordVerifiedParseFailure(any(), any(), any(), any(), any());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.RS), eq(StateIngestionStrategy.VERIFIED_ADAPTER),
                eq(StateIngestionOutcome.FETCH_FAILED), anyString(), contains("not-the-nota-page"));
    }

    @Test
    void parse_portalRejection_rescuedWhenChaveReconsultRendersDanfe() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any()))
                .thenThrow(new SefazPortalRejectionException("227", true));
        var reconsultAdapter = mock(SefazAdapter.class);
        var expected = sampleParsed();
        when(reconsultAdapter.parseHtml(anyString(), anyString(), any())).thenReturn(expected);
        when(rsChaveReconsult.reconsult(CHAVE_RS)).thenReturn(new FetchedDocument(
                reconsultAdapter, "<html>satweb danfe</html>", CHAVE_RS, UnidadeFederativa.RS, "https://www.sefaz.rs.gov.br/NFCE"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, "<html>rejection page</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var parsed = service.parse(fetched, null);

        assertSame(expected, parsed);
        verify(stateCoverage, never()).recordVerifiedParseFailure(any(), any(), any(), any(), any());
    }

    @Test
    void parse_portalRejection_reconsultFails_propagatesOriginalRejection() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var rejection = new SefazPortalRejectionException("227", true);
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(rejection);
        // Nota genuinely absent at SEFAZ: the legacy portal renders no items either.
        when(rsChaveReconsult.reconsult(CHAVE_RS)).thenThrow(new ReceiptParseException("no-items-found"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, "<html>rejection page</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var thrown = assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        assertSame(rejection, thrown);
        // The parser understood the page (a cStat rejection) — not a layout regression.
        verify(stateCoverage, never()).recordVerifiedParseFailure(any(), any(), any(), any(), any());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.RS), eq(StateIngestionStrategy.VERIFIED_ADAPTER),
                eq(StateIngestionOutcome.FETCH_FAILED), anyString(), anyString());
    }

    @Test
    void parse_portalRejection_nonRsChave_neverTriesReconsult() {
        var scAdapter = mockAdapterFor(UnidadeFederativa.SC);
        var rejection = new SefazPortalRejectionException("227", false);
        when(scAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(rejection);
        var service = new SefazIngestionService(List.of(scAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(scAdapter, "<html>rejection page</html>", CHAVE_SC,
                UnidadeFederativa.SC, "https://sat.sef.sc.gov.br/x");

        var thrown = assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        assertSame(rejection, thrown);
        verifyNoInteractions(rsChaveReconsult);
    }

    @Test
    void reparseStored_rejectionPage_rescuedByChaveReconsult() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any()))
                .thenThrow(new SefazPortalRejectionException("227", true));
        var reconsultAdapter = mock(SefazAdapter.class);
        var expected = sampleParsed();
        when(reconsultAdapter.parseHtml(anyString(), anyString(), any())).thenReturn(expected);
        when(rsChaveReconsult.reconsult(CHAVE_RS)).thenReturn(new FetchedDocument(
                reconsultAdapter, "<html>satweb danfe</html>", CHAVE_RS, UnidadeFederativa.RS, "https://www.sefaz.rs.gov.br/NFCE"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var parsed = service.reparseStored(UnidadeFederativa.RS, "<html>rejection page</html>", CHAVE_RS, null);

        assertSame(expected, parsed);
    }

    @Test
    void reparseStored_rejectionPage_reconsultFails_propagatesOriginalRejection() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var rejection = new SefazPortalRejectionException("227", true);
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(rejection);
        when(rsChaveReconsult.reconsult(CHAVE_RS)).thenThrow(new SefazFetchException("RS"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ReceiptParseException.class,
                () -> service.reparseStored(UnidadeFederativa.RS, "<html>rejection page</html>", CHAVE_RS, null));

        assertSame(rejection, thrown);
    }

    @Test
    void parse_verifiedStateSuccess_recordsNoRegression() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, "<html>ok</html>", CHAVE_RS, UnidadeFederativa.RS, null);

        service.parse(fetched, null);

        verify(stateCoverage, never()).recordVerifiedParseFailure(any(), any(), any(), any(), any());
    }

    @Test
    void parse_experimentalSuccess_recordsQrPortalTelemetry() {
        var expected = sampleParsed();
        var generic = new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", true, 1, 0, "gov.br") {
            @Override
            public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
                return expected;
            }
        };
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>ba</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched, null);

        assertSame(expected, parsed);
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL), anyString());
    }

    @Test
    void parse_experimentalParseFailure_noInfosimples_reportsExhaustedAndThrowsExperimentalKey() {
        var generic = genericAdapter("<html>weird layout</html>");
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.parse(fetched, null));

        assertEquals("receipt.state.experimental_failed", thrown.getMessageKey());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL),
                eq(StateIngestionOutcome.PARSE_FAILED), anyString(), anyString());
        verify(stateCoverage).reportExhausted(eq(UnidadeFederativa.BA), any(), eq(CHAVE_BA), eq(QR_URL_BA),
                anyString(), anyString());
    }

    @Test
    void parse_experimentalParseFailure_infosimplesRescues() {
        var generic = genericAdapter("<html>weird layout</html>");
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched, null);

        assertSame(preParsed, parsed);
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.INFOSIMPLES), anyString());
    }

    @Test
    void fetch_experimentalFetchFailure_noInfosimples_reportsExhaustedAndThrowsExperimentalKey() {
        var generic = genericAdapter(null); // portal unreachable
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA, null));

        assertEquals("receipt.state.experimental_failed", thrown.getMessageKey());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL),
                eq(StateIngestionOutcome.FETCH_FAILED), anyString(), anyString());
        verify(stateCoverage).reportExhausted(eq(UnidadeFederativa.BA), any(), eq(CHAVE_BA), eq(QR_URL_BA),
                anyString(), any());
    }

    @Test
    void fetch_experimentalChain_infosimplesNoData_carriesTheAnswerOnTheFailure() {
        var generic = genericAdapter(null); // portal unreachable
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA)))
                .thenThrow(new InfosimplesNoDataException("infosimples.not-found", 612));
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA, null));

        assertEquals(InfosimplesOutcome.NO_DATA, thrown.getInfosimplesOutcome());
        assertFalse(thrown.isPortalUnavailable());
    }

    @Test
    void fetch_experimentalChain_infosimplesUnstable_isPortalUnavailableWithFailedOutcome() {
        var generic = genericAdapter(null); // portal unreachable
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA)))
                .thenThrow(new InfosimplesProviderException(615));
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA, null));

        assertEquals(InfosimplesOutcome.FAILED, thrown.getInfosimplesOutcome());
        assertTrue(thrown.isPortalUnavailable());
    }

    @Test
    void fetch_experimentalChain_infosimplesDisabled_leavesTheOutcomeEmpty() {
        var generic = genericAdapter(null);
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA, null));

        assertNull(thrown.getInfosimplesOutcome());
    }

    @Test
    void fetch_deviceFetchUfExhausted_recordsNoEvidence() {
        // PE fails server-side BY DESIGN (portal blocks our datacenter IP) — the
        // handoff to on-device fetch must not pile up EXHAUSTED evidence that
        // would later lock the state at submit.
        var qrUrlPe = "https://nfce.sefaz.pe.gov.br/nfce/consulta?p=" + CHAVE_PE + "|2|1|1|deadbeef";
        var generic = genericAdapter(null); // portal unreachable from our IP
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(qrUrlPe, null));

        verify(stateCoverage, never()).reportExhausted(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fetch_experimentalFetchFailure_infosimplesRescues() {
        var generic = genericAdapter(null);
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetch(QR_URL_BA, null);

        assertSame(preParsed, fetched.preParsed());
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.INFOSIMPLES), anyString());
    }

    @Test
    void fetch_verifiedStateFailure_neverTouchesStateCoverage() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_RS, null));

        verifyNoInteractions(stateCoverage);
    }

    @Test
    void supportsBareChave_reconsultableAndNativeTrue_svrsNeedsInfosimples() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.requiresQrSignature()).thenReturn(true); // RS é SVRS (QR), mas tem legado grátis
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS); // nativo (default requiresQrSignature=false)
        var chaveMs = "50" + CHAVE_RS.substring(2);

        var withoutInfosimples = new SefazIngestionService(
                List.of(rsAdapter, spAdapter, msAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        assertTrue(withoutInfosimples.supportsBareChave(CHAVE_RS), "RS: reconsulta legada grátis (SAT-WEB)");
        assertTrue(withoutInfosimples.supportsBareChave(chaveMs), "MS: consulta por chave nativamente");
        assertTrue(!withoutInfosimples.supportsBareChave(CHAVE_SP), "SP: QR-only, sem legado nem Infosimples");

        var withInfosimples = new SefazIngestionService(
                List.of(rsAdapter, spAdapter, msAdapter), Optional.of(mock(InfosimplesService.class)), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        assertTrue(withInfosimples.supportsBareChave(CHAVE_SP), "SP: via Infosimples quando habilitado");
    }

    @Test
    void fetch_bareChaveRequiringQrSignature_triesFreeLegacyReconsultBeforeInfosimples() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.requiresQrSignature()).thenReturn(true); // chave pura não dá pra raspar
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var legacy = new FetchedDocument(rsAdapter, "<html>legacy</html>", CHAVE_RS, UnidadeFederativa.RS, "satweb");
        when(chaveReconsult.reconsult(CHAVE_RS)).thenReturn(legacy);

        var fetched = service.fetch(CHAVE_RS, null);

        assertSame(legacy, fetched);
        verify(chaveReconsult).reconsult(CHAVE_RS);
        verifyNoInteractions(infosimples); // a reconsulta grátis resolveu — nada pago
    }

    @Test
    void fetch_bareChaveBaReconsult_ledgersEverySolveItBought() {
        var baAdapter = mockAdapterFor(UnidadeFederativa.BA);
        when(baAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(baAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var userId = UUID.randomUUID();
        var danfe = new FetchedDocument(baAdapter, "<html>danfe</html>", CHAVE_BA, UnidadeFederativa.BA, "ba");
        when(chaveReconsult.reconsult(CHAVE_BA)).thenAnswer(invocation -> {
            CaptchaSolveTracker.assertSpendAllowed();
            CaptchaSolveTracker.recordSolve(); // first code rejected by the portal
            CaptchaSolveTracker.recordSolve(); // re-solved, accepted
            return danfe;
        });

        var fetched = service.fetch(CHAVE_BA, userId);

        assertSame(danfe, fetched);
        verify(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
        verify(paidApiGuard, times(2)).recordSuccess(userId, PaidApiService.CAPTCHA_SOLVE, "BA", null);
    }

    @Test
    void fetch_bareChaveBaReconsult_failedAttemptStillLedgersItsSolves() {
        var baAdapter = mockAdapterFor(UnidadeFederativa.BA);
        when(baAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(baAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        when(chaveReconsult.reconsult(CHAVE_BA)).thenAnswer(invocation -> {
            CaptchaSolveTracker.recordSolve();
            throw new SefazFetchException("BA");
        });

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_BA, null));

        verify(paidApiGuard).recordFailure(null, PaidApiService.CAPTCHA_SOLVE, "BA", null);
    }

    @Test
    void fetch_bareChaveBaReconsult_userOverTheCaptchaCap_buysNoSolve() {
        var baAdapter = mockAdapterFor(UnidadeFederativa.BA);
        when(baAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(baAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var userId = UUID.randomUUID();
        doThrow(new PaidApiQuotaExceededException("CAPTCHA_SOLVE"))
                .when(paidApiGuard).assertWithinDailyCap(userId, PaidApiService.CAPTCHA_SOLVE);
        when(chaveReconsult.reconsult(CHAVE_BA)).thenAnswer(invocation -> {
            CaptchaSolveTracker.assertSpendAllowed(); // the solver's own gate refuses the purchase
            return null;
        });

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_BA, userId));

        verify(paidApiGuard, never()).recordSuccess(any(), any(), any(), any());
        verify(paidApiGuard, never()).recordFailure(any(), any(), any(), any());
    }

    @Test
    void fetch_bareChaveFreeReconsult_skipsTheCaptchaMetering() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        when(chaveReconsult.reconsult(CHAVE_RS))
                .thenReturn(new FetchedDocument(rsAdapter, "<html/>", CHAVE_RS, UnidadeFederativa.RS, "satweb"));

        service.fetch(CHAVE_RS, UUID.randomUUID());

        verifyNoInteractions(paidApiGuard);
    }

    @Test
    void fetch_bareChaveInfosimplesZeroItems_failsWithoutTheNoPathAlert() {
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP)))
                .thenThrow(new ReceiptParseException("no-items-found"));
        var service = new SefazIngestionService(List.of(spAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_SP, null));

        assertTrue(thrown.isNoItemsFound());
        verifyNoInteractions(operationalAlert);
    }

    @Test
    void fetch_bareChaveNoPath_alertsAdminAndFails() {
        // SP: QR-only, não reconsultável, Infosimples desabilitado → alerta admin + falha.
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var service = new SefazIngestionService(List.of(spAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_SP, null));
        verify(operationalAlert).alertAdmin(contains("SP"), anyString());
        verifyNoInteractions(chaveReconsult); // SP não é reconsultável
    }

    @Test
    void fetchedDocument_backwardCompatConstructorSetsNullPreParsed() {
        var adapter = mock(SefazAdapter.class);
        var doc = new FetchedDocument(adapter, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);

        assertNotNull(doc.adapter());
        assertNull(doc.preParsed());
    }

    // ── Captcha cost ledger: charge ONLY real solves ─────────────────────────

    @Test
    void fetch_captchaFreeScrape_recordsNoCaptchaCost() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetch(CHAVE_RS, null);

        verify(paidApiGuard, never()).recordSuccess(any(), eq(PaidApiService.CAPTCHA_SOLVE), any(), any());
        verify(paidApiGuard, never()).recordFailure(any(), eq(PaidApiService.CAPTCHA_SOLVE), any(), any());
    }

    @Test
    void fetch_realSolves_recordOneLedgerEntryPerToken() {
        var chaveMs = "50260777863223012709650180004455861342485537";
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenAnswer(invocation -> {
            CaptchaSolveTracker.recordSolve();
            CaptchaSolveTracker.recordSolve(); // first token rejected, re-solved
            return "<html>danfe</html>";
        });
        var service = new SefazIngestionService(List.of(msAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetch(chaveMs, null);

        verify(paidApiGuard, times(2)).recordSuccess(null, PaidApiService.CAPTCHA_SOLVE, "MS", null);
        verify(paidApiGuard, never()).recordFailure(any(), eq(PaidApiService.CAPTCHA_SOLVE), any(), any());
    }

    @Test
    void fetch_experimentalCaptchaWallAfterPaidSolve_recordsTheSolveAsFailure() {
        var generic = new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", true, 1, 0, "gov.br") {
            @Override
            public String fetchHtml(String qrPayload) {
                CaptchaSolveTracker.recordSolve(); // token was bought, portal rejected it
                throw new ExperimentalCaptchaWallException("BA", "RECAPTCHA_V2", "site-key", "<html/>");
            }
        };
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetch(QR_URL_BA, null);

        verify(paidApiGuard).recordFailure(null, PaidApiService.CAPTCHA_SOLVE, "BA", null);
    }

    @Test
    void fetch_infosimplesOnlyAdapterImmediateThrow_recordsNoCaptchaFailure() {
        // The CE adapter throws without ever solving — its "failure" must not
        // feed the captcha breaker with noise.
        var ceAdapter = mockAdapterFor(UnidadeFederativa.CE);
        var chaveCe = "23260412345678000190650010000123451123456780";
        when(ceAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("CE"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(chaveCe), eq(UnidadeFederativa.CE))).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(ceAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        service.fetch(chaveCe, null);

        verify(paidApiGuard, never()).recordFailure(any(), eq(PaidApiService.CAPTCHA_SOLVE), any(), any());
        verify(paidApiGuard, never()).recordSuccess(any(), eq(PaidApiService.CAPTCHA_SOLVE), any(), any());
    }

    @Test
    void fetch_deterministicFailureAfterPaidSolve_stillLedgersTheSolve() {
        var chaveMs = "50260777863223012709650180004455861342485537";
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenAnswer(invocation -> {
            CaptchaSolveTracker.recordSolve(); // solve happened, then viewstate was missing
            throw new ReceiptParseException("captcha-viewstate-missing");
        });
        var service = new SefazIngestionService(List.of(msAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(ReceiptParseException.class, () -> service.fetch(chaveMs, null));

        verify(paidApiGuard).recordFailure(null, PaidApiService.CAPTCHA_SOLVE, "MS", null);
    }

    // ── LLM parse-rescue layer (between the free parser and Infosimples) ──────

    @Test
    void parse_experimentalParseFailure_llmRescues_beforeInfosimples() {
        var generic = genericAdapter("<html>weird layout</html>");
        var infosimples = mock(InfosimplesService.class);
        var rescued = sampleParsed();
        when(htmlReceiptRescue.rescue(eq("<html>weird layout</html>"), eq(CHAVE_BA),
                eq(UnidadeFederativa.BA), eq(QR_URL_BA), any())).thenReturn(rescued);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched, null);

        assertSame(rescued, parsed);
        verify(infosimples, never()).fetchParsed(any(), any());
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.LLM_RESCUE), anyString());
    }

    @Test
    void parse_verifiedParseFailure_llmRescues_andStillRecordsRegression() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any()))
                .thenThrow(new ReceiptParseException("no-items-found"));
        var rescued = sampleParsed();
        when(htmlReceiptRescue.rescue(eq(CHANGED_LAYOUT_RS), eq(CHAVE_RS),
                eq(UnidadeFederativa.RS), any(), any())).thenReturn(rescued);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, CHANGED_LAYOUT_RS, CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var parsed = service.parse(fetched, null);

        assertSame(rescued, parsed);
        // The rescue saves the user's receipt, but the parser regression signal must still fire.
        verify(stateCoverage).recordVerifiedParseFailure(eq(UnidadeFederativa.RS), eq(CHAVE_RS),
                eq("https://sefaz.rs.gov.br/x"), anyString(), eq(CHANGED_LAYOUT_RS));
    }

    @Test
    void parse_experimentalParseFailure_llmFails_infosimplesStillRescues() {
        var generic = genericAdapter("<html>weird layout</html>");
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched, null); // rescue mock returns null = didn't rescue

        assertSame(preParsed, parsed);
        verify(htmlReceiptRescue).rescue(eq("<html>weird layout</html>"), eq(CHAVE_BA),
                eq(UnidadeFederativa.BA), eq(QR_URL_BA), any());
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.INFOSIMPLES), anyString());
    }

    @Test
    void parse_portalRejection_neverSpendsOnLlmRescue() {
        // A rejection page carries no DANFE — the LLM layer must not burn a call on it.
        var scAdapter = mockAdapterFor(UnidadeFederativa.SC);
        when(scAdapter.parseHtml(anyString(), anyString(), any()))
                .thenThrow(new SefazPortalRejectionException("227", false));
        var service = new SefazIngestionService(List.of(scAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(scAdapter, "<html>rejection page</html>", CHAVE_SC,
                UnidadeFederativa.SC, "https://sat.sef.sc.gov.br/x");

        assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        verifyNoInteractions(htmlReceiptRescue);
    }

    @Test
    void parse_contingencyNoItems_neverSpendsOnLlmRescue() {
        // tpEmis=9 (offline contingency), chave position 35 = '9': SEFAZ has no items yet.
        var chaveContingency = CHAVE_RS.substring(0, 34) + "9" + CHAVE_RS.substring(35);
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any()))
                .thenThrow(new ReceiptParseException("no-items-found"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(rsAdapter, "<html>no items</html>", chaveContingency,
                UnidadeFederativa.RS, null);

        assertThrows(ReceiptParseException.class, () -> service.parse(fetched, null));

        verifyNoInteractions(htmlReceiptRescue);
    }

    // ── Generic reactive device-fetch (flag ON): defer BEFORE Infosimples ────

    /** A generic-ON policy: any UF may device-fetch; trust (index) still PE-only. */
    private final PrefetchPolicy genericPrefetch = new PrefetchPolicy("PE", true);

    private SefazIngestionService genericService(SefazAdapter adapter, Optional<InfosimplesService> infosimples) {
        return new SefazIngestionService(List.of(adapter), infosimples, paidApiGuard, stateCoverage,
                rsChaveReconsult, chaveReconsult, operationalAlert, genericPrefetch, htmlReceiptRescue);
    }

    @Test
    void fetch_genericOn_ipBlock_defersToDeviceFetchBeforeInfosimples() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        var service = genericService(rsAdapter, Optional.of(infosimples));

        // canDeviceRetry=true + generic ON + rescuable fetch failure → device-fetch signal.
        assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(CHAVE_RS, null, true));
        // The paid fallback must NOT run — device-fetch comes first.
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_genericOn_appliesToVerifiedStatesToo() {
        // Verified states (GO/SP/…) never got the device-fetch lifeline before; now they do.
        var chaveGo = "52260412345678000190650010000123451123456780"; // UF 52 = GO
        var goAdapter = mockAdapterFor(UnidadeFederativa.GO);
        when(goAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("GO"));
        var infosimples = mock(InfosimplesService.class);
        var service = genericService(goAdapter, Optional.of(infosimples));

        assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(chaveGo, null, true));
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_genericOn_clientCannotRetry_fallsToInfosimplesAsBefore() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_RS), eq(UnidadeFederativa.RS))).thenReturn(sampleParsed());
        var service = genericService(rsAdapter, Optional.of(infosimples));

        var fetched = service.fetch(CHAVE_RS, null, false); // web/photo client — can't device-fetch

        assertNotNull(fetched.preParsed());
        verify(infosimples).fetchParsed(CHAVE_RS, UnidadeFederativa.RS);
    }

    @Test
    void fetch_genericOn_captchaWall_notDeferred_goesToInfosimples() {
        // A captcha the server couldn't pass — the phone can't either, so device-fetch
        // would just time out. Must NOT defer; fall to Infosimples (or fail) as before.
        var chaveMs = "50260777863223012709650180004455861342485537";
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);
        when(msAdapter.fetchHtml(anyString())).thenThrow(new CaptchaUnavailableException("MS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(chaveMs), eq(UnidadeFederativa.MS))).thenReturn(sampleParsed());
        var service = genericService(msAdapter, Optional.of(infosimples));

        var fetched = service.fetch(chaveMs, null, true);

        assertNotNull(fetched.preParsed());
        verify(infosimples).fetchParsed(chaveMs, UnidadeFederativa.MS);
    }

    @Test
    void fetch_genericOn_deterministicFailure_notDeferred_propagates() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var deterministic = new SefazDeterministicFetchException("RS");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(deterministic);
        var infosimples = mock(InfosimplesService.class);
        var service = genericService(rsAdapter, Optional.of(infosimples));

        var thrown = assertThrows(SefazDeterministicFetchException.class, () -> service.fetch(CHAVE_RS, null, true));

        assertSame(deterministic, thrown);
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_flagOff_deviceFetchAvailableIsInert() {
        // Same inputs as the defer test, but policy OFF (default) → identical old behavior.
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_RS), eq(UnidadeFederativa.RS))).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard,
                stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue); // prefetchPolicy = OFF

        var fetched = service.fetch(CHAVE_RS, null, true); // deviceFetchAvailable=true but flag OFF

        assertNotNull(fetched.preParsed());
        verify(infosimples).fetchParsed(CHAVE_RS, UnidadeFederativa.RS); // no deferral — Infosimples runs
    }

    @Test
    void fetch_genericOn_experimentalState_defersToDeviceFetch() {
        // Experimental state (no dedicated adapter), portal unreachable from our IP: with
        // generic ON + device-capable client, defer to device-fetch instead of Infosimples.
        var generic = genericAdapter(null); // httpFetch throws → SefazFetchException
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard,
                stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, genericPrefetch, htmlReceiptRescue);

        assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA, null, true));
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_genericOn_scrapeSucceeds_noDeviceFetchNoInfosimples() {
        // The happy path is unaffected by the flag: a successful scrape just returns.
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = genericService(rsAdapter, Optional.of(infosimples));

        var fetched = service.fetch(CHAVE_RS, null, true);

        assertSame(rsAdapter, fetched.adapter());
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetchViaInfosimplesFallback_returnsPreParsedOnSuccess() {
        // CHAVE_SP has a valid check digit — the fallback proceeds to the provider and records 200.
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.SP)), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var fetched = service.fetchViaInfosimplesFallback(CHAVE_SP, UnidadeFederativa.SP, null);

        assertSame(preParsed, fetched.preParsed());
        verify(paidApiGuard).recordSuccess(null, PaidApiService.INFOSIMPLES, "SP", "infosimples", 200);
    }

    @Test
    void fetchViaInfosimplesFallback_corruptedChave_skipsPaidCall() {
        // A chave with a broken mod-11 check digit (QR read corrupted) must NOT reach the paid
        // provider — it would always 607. Fail fast with "chave-corrupted", no ledger row.
        var corrupted = CHAVE_SP.substring(0, 43) + (CHAVE_SP.charAt(43) == '0' ? '1' : '0');
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.SP)), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ReceiptParseException.class,
                () -> service.fetchViaInfosimplesFallback(corrupted, UnidadeFederativa.SP, null));

        assertEquals("chave-corrupted", thrown.getArguments()[0]);
        verifyNoInteractions(infosimples);
        verifyNoInteractions(paidApiGuard);
    }

    @Test
    void fetchViaInfosimplesFallback_recordsDeterministicCodeOnNoData() {
        // Item 2: the provider's result code (612) lands in the ledger, no longer blind.
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP)))
                .thenThrow(new InfosimplesNoDataException("infosimples.not-found", 612));
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.SP)), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(InfosimplesNoDataException.class,
                () -> service.fetchViaInfosimplesFallback(CHAVE_SP, UnidadeFederativa.SP, null));

        verify(paidApiGuard).recordDeterministicFailure(null, PaidApiService.INFOSIMPLES, "SP", "infosimples", 612);
    }

    @Test
    void fetchViaInfosimplesFallback_zeroItems_isLedgeredAsDeterministicNotBreakerFailure() {
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(eq(CHAVE_SP), eq(UnidadeFederativa.SP)))
                .thenThrow(new ReceiptParseException("no-items-found"));
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.SP)), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(ReceiptParseException.class,
                () -> service.fetchViaInfosimplesFallback(CHAVE_SP, UnidadeFederativa.SP, null));

        assertTrue(thrown.isNoItemsFound());
        verify(paidApiGuard).recordDeterministicFailure(null, PaidApiService.INFOSIMPLES, "SP", "infosimples", 200);
        verify(paidApiGuard, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    void fetch_freeOnlyPolicy_skipsInfosimplesAndBlocksCaptchaSpend() {
        var adapter = mockAdapterFor(UnidadeFederativa.SP);
        when(adapter.fetchHtml(CHAVE_SP)).thenAnswer(invocation -> {
            // A solver asked for a token during a free-only attempt must be refused.
            assertThrows(RuntimeException.class, CaptchaSolveTracker::assertSpendAllowed);
            throw new SefazFetchException("SP");
        });
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(adapter), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(SefazFetchException.class,
                () -> service.fetch(CHAVE_SP, null, false, IngestSpendPolicy.FREE_ONLY));

        verifyNoInteractions(infosimples);
        verify(paidApiGuard, never()).assertWithinDailyCap(any(), any());
        CaptchaSolveTracker.blockSpend(false);
    }

    @Test
    void parse_freeOnlyPolicy_experimentalParseFailure_spendsOnNeitherLlmNorInfosimples() {
        var generic = genericAdapter("<html>weird layout</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var thrown = assertThrows(ReceiptParseException.class,
                () -> service.parse(fetched, null, IngestSpendPolicy.FREE_ONLY));

        assertEquals("receipt.parse.failed", thrown.getMessageKey());
        verifyNoInteractions(infosimples);
        verifyNoInteractions(htmlReceiptRescue);
        verify(stateCoverage, never()).reportExhausted(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fetchViaInfosimplesFallback_noProvider_throwsRescuable() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(),
                paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        assertThrows(SefazFetchException.class,
                () -> service.fetchViaInfosimplesFallback(CHAVE_RS, UnidadeFederativa.RS, null));
    }

    // ── Deterministic portal 4xx: never spend Infosimples ────────────────────

    @Test
    void fetch_deterministic4xx_propagatesWithoutSpendingInfosimples() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var deterministicEx = new SefazDeterministicFetchException("RS");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(deterministicEx);
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage, rsChaveReconsult, chaveReconsult, operationalAlert, prefetchPolicy, htmlReceiptRescue);

        var thrown = assertThrows(SefazDeterministicFetchException.class, () -> service.fetch(CHAVE_RS, null));

        assertSame(deterministicEx, thrown);
        verify(infosimples, never()).fetchParsed(any(), any());
    }
}
