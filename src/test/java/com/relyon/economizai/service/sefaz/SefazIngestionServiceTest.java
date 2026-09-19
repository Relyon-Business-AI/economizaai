package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import com.relyon.economizai.exception.ExperimentalStateFailedException;
import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.SefazFetchException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.StateIngestionOutcome;
import com.relyon.economizai.model.enums.StateIngestionStrategy;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.paidapi.PaidApiGuardService;
import com.relyon.economizai.service.sefaz.captcha.CaptchaSolver;
import com.relyon.economizai.service.sefaz.SefazIngestionService.FetchedDocument;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SefazIngestionServiceTest {

    // UF code 43 → RS.
    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
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
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.PE))), Optional.empty(), paidApiGuard, stateCoverage);
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
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.PE))), Optional.empty(), paidApiGuard, stateCoverage);
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
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage);
        var xml = new String(new ClassPathResource("fixtures/sefaz/pe/pe-novo-israel-4items.xml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        var fetched = service.fromClientContent(xml, CHAVE_PE, UnidadeFederativa.PE, "http://nfce.sefaz.pe.gov.br/x");
        var parsed = service.parse(fetched);

        assertEquals(4, parsed.items().size());
        assertEquals(0, parsed.totalAmount().compareTo(new BigDecimal("24.13")));
        assertTrue(parsed.marketName().contains("NOVO ISRAEL"));
    }

    @Test
    void fromClientContent_rejectsUnsupportedUf() {
        var service = new SefazIngestionService(
                List.of(new FakeAdapter(Set.of(UnidadeFederativa.RS))), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(UnsupportedStateException.class,
                () -> service.fromClientContent("body " + CHAVE_PE, CHAVE_PE, UnidadeFederativa.PE, null));
    }

    @Test
    void constructor_registersAllSupportedStates() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS, UnidadeFederativa.SC);
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);

        var service = new SefazIngestionService(List.of(rsAdapter, spAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        // RS routes to the RS adapter via fetch.
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>rs</html>");
        var fetched = service.fetch(CHAVE_RS);
        assertSame(rsAdapter, fetched.adapter());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
    }

    @Test
    void constructor_throwsWhenTwoAdaptersClaimSameUf() {
        var first = new FakeAdapter(Set.of(UnidadeFederativa.RS));
        var second = new FakeAdapter(Set.of(UnidadeFederativa.RS, UnidadeFederativa.SC));
        List<SefazAdapter> adapters = List.of(first, second);

        var error = assertThrows(IllegalStateException.class,
                () -> new SefazIngestionService(adapters, Optional.empty(), paidApiGuard, stateCoverage));
        assertTrue(error.getMessage().contains("RS"));
    }

    @Test
    void constructor_sameAdapterInstanceListedTwiceIsNotADuplicate() {
        var shared = mockAdapterFor(UnidadeFederativa.RS);

        // Same instance reappearing must not trip the guard (prior == adapter).
        var service = new SefazIngestionService(List.of(shared, shared), Optional.empty(), paidApiGuard, stateCoverage);

        when(shared.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var fetched = service.fetch(CHAVE_RS);
        assertSame(shared, fetched.adapter());
    }

    @Test
    void fetch_picksAdapterByUfSanitizesCpfAndSetsSourceUrlForHttp() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var httpPayload = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS;
        when(rsAdapter.fetchHtml(httpPayload))
                .thenReturn("<html>cliente 123.456.789-00 fim</html>");
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var fetched = service.fetch(httpPayload);

        assertSame(rsAdapter, fetched.adapter());
        assertEquals(CHAVE_RS, fetched.chave());
        assertEquals(UnidadeFederativa.RS, fetched.uf());
        assertEquals(httpPayload, fetched.sourceUrl());
        assertTrue(fetched.html().contains("***.***.***-**"));
        assertTrue(!fetched.html().contains("123.456.789-00"));
    }

    @Test
    void fetch_setsNullSourceUrlForNonHttpPayload() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var fetched = service.fetch(CHAVE_RS);

        assertNull(fetched.sourceUrl());
    }

    @Test
    void fetch_usesAdapterPreflightWhenSecurityUrlDoesNotExposeChave() {
        var scAdapter = mockAdapterFor(UnidadeFederativa.SC);
        var securityUrl = "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc";
        when(scAdapter.preflightChave(securityUrl)).thenReturn(Optional.of(CHAVE_SC));
        when(scAdapter.fetchHtml(securityUrl)).thenReturn("<html>sc</html>");
        var service = new SefazIngestionService(List.of(scAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var fetched = service.fetch(securityUrl);

        assertSame(scAdapter, fetched.adapter());
        assertEquals(CHAVE_SC, fetched.chave());
        assertEquals(UnidadeFederativa.SC, fetched.uf());
        assertEquals(securityUrl, fetched.sourceUrl());
    }

    @Test
    void fetch_throwsUnsupportedStateWhenNoAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(UnsupportedStateException.class, () -> service.fetch(CHAVE_SP));
    }

    @Test
    void fetch_throwsInvalidQrPayloadForGarbageInput() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(InvalidQrPayloadException.class, () -> service.fetch("not-a-chave"));
    }

    @Test
    void parse_delegatesToFetchedAdapter() {
        var rsAdapter = mock(SefazAdapter.class);
        var fetched = new FetchedDocument(rsAdapter, "<html>x</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://example/src");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>x</html>", CHAVE_RS, "https://example/src"))
                .thenReturn(expected);

        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage);
        var parsed = service.parse(fetched);

        assertSame(expected, parsed);
    }

    @Test
    void ingest_fetchesThenParses() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>raw</html>");
        var expected = sampleParsed();
        when(rsAdapter.parseHtml(eq("<html>raw</html>"), eq(CHAVE_RS), any()))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var parsed = service.ingest(CHAVE_RS);

        assertSame(expected, parsed);
        verify(rsAdapter).fetchHtml(CHAVE_RS);
    }

    @Test
    void reparseStored_usesAdapterForUf() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var expected = sampleParsed();
        when(rsAdapter.parseHtml("<html>stored</html>", CHAVE_RS, "src"))
                .thenReturn(expected);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var parsed = service.reparseStored(UnidadeFederativa.RS, "<html>stored</html>", CHAVE_RS, "src");

        assertSame(expected, parsed);
    }

    @Test
    void reparseStored_throwsUnsupportedStateWhenNoAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(CHAVE_RS);

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
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(chaveMs);

        assertSame(preParsed, fetched.preParsed());
    }

    @Test
    void fetch_doesNotSpendInfosimplesOnDeterministicParseFailure() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var deterministicEx = new ReceiptParseException("captcha-sitekey-missing");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(deterministicEx);
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var thrown = assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS));

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
        var service = new SefazIngestionService(List.of(spAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(CHAVE_SP); // bare chave, no QR signature

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
        var service = new SefazIngestionService(List.of(spAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_SP));
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
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(chaveMs); // bare chave

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
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(httpUrl); // full signed URL — scrapeable

        assertSame(rsAdapter, fetched.adapter());
        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void requireSupported_passesForRegisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage);

        service.requireSupported(UnidadeFederativa.RS);
    }

    @Test
    void requireSupported_throwsForUnregisteredUf() {
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(UnsupportedStateException.class,
                () -> service.requireSupported(UnidadeFederativa.SP));
    }

    @Test
    void fetch_doesNotCallInfosimplesWhenPrimarySucceeds() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenReturn("<html>ok</html>");
        var infosimples = mock(InfosimplesService.class);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        service.fetch(CHAVE_RS);

        verify(infosimples, never()).fetchParsed(any(), any());
    }

    @Test
    void fetch_rethrowsPrimaryExceptionWhenInfosimplesDisabled() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var primaryEx = new SefazFetchException("RS");
        when(rsAdapter.fetchHtml(anyString())).thenThrow(primaryEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        var thrown = assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_RS));

        assertSame(primaryEx, thrown);
    }

    @Test
    void fetch_propagatesInfosimplesExceptionWhenBothFail() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var infosimples = mock(InfosimplesService.class);
        when(infosimples.fetchParsed(any(), any())).thenThrow(new ReceiptParseException("infosimples.error"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        assertThrows(ReceiptParseException.class, () -> service.fetch(CHAVE_RS));
    }

    @Test
    void parse_returnsPreParsedReceiptDirectly() {
        var preParsed = sampleParsed();
        var fetched = new FetchedDocument(null, null, CHAVE_RS, UnidadeFederativa.RS, null, preParsed);
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage);

        var result = service.parse(fetched);

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
        var service = new SefazIngestionService(List.of(mockAdapterFor(UnidadeFederativa.RS)), Optional.empty(), paidApiGuard, stateCoverage);

        var parsed = service.parse(fetched);

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
        var service = new SefazIngestionService(List.of(msAdapter), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(chaveMs);

        assertSame(preParsed, fetched.preParsed());
        assertEquals(UnidadeFederativa.MS, fetched.uf());
    }

    // ── Experimental fallback chain (states without a dedicated adapter) ──────

    // UF code 29 → BA (no dedicated adapter — served by the experimental chain).
    private static final String CHAVE_BA = "29260412345678000190650010000123451123456780";
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
            protected String httpGet(String url) {
                if (htmlOrNull == null) throw new RestClientException("portal down");
                return htmlOrNull;
            }
        };
    }

    @Test
    void constructor_gapFillsUnclaimedUfsWithGenericAdapter() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var generic = genericAdapter("<html>ba</html>");
        var service = new SefazIngestionService(List.of(rsAdapter, generic), Optional.empty(), paidApiGuard, stateCoverage);

        var fetched = service.fetch(QR_URL_BA);

        assertSame(generic, fetched.adapter());
        assertEquals(UnidadeFederativa.BA, fetched.uf());
        assertTrue(service.getVerifiedStates().contains(UnidadeFederativa.RS));
        assertTrue(service.experimentalStates().contains(UnidadeFederativa.BA));
        assertTrue(!service.experimentalStates().contains(UnidadeFederativa.RS));
    }

    @Test
    void constructor_disabledGenericAdapterLeavesUfsUnsupported() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var disabledGeneric = new GenericQrPortalAdapter(RestClient.builder(), NO_CAPTCHA, 1000, "test", false, 1, 0, "gov.br");
        var service = new SefazIngestionService(List.of(rsAdapter, disabledGeneric), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(UnsupportedStateException.class, () -> service.requireSupported(UnidadeFederativa.BA));
    }

    @Test
    void parse_verifiedStateParseFailure_recordsRegressionAndRethrows() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var parseEx = new ReceiptParseException("no-items-found");
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenThrow(parseEx);
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);
        var fetched = new FetchedDocument(rsAdapter, "<html>changed layout</html>", CHAVE_RS,
                UnidadeFederativa.RS, "https://sefaz.rs.gov.br/x");

        var thrown = assertThrows(ReceiptParseException.class, () -> service.parse(fetched));

        assertSame(parseEx, thrown);
        verify(stateCoverage).recordVerifiedParseFailure(eq(UnidadeFederativa.RS), eq(CHAVE_RS),
                eq("https://sefaz.rs.gov.br/x"), anyString(), eq("<html>changed layout</html>"));
    }

    @Test
    void parse_verifiedStateSuccess_recordsNoRegression() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.parseHtml(anyString(), anyString(), any())).thenReturn(sampleParsed());
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);
        var fetched = new FetchedDocument(rsAdapter, "<html>ok</html>", CHAVE_RS, UnidadeFederativa.RS, null);

        service.parse(fetched);

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
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage);
        var fetched = new FetchedDocument(generic, "<html>ba</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched);

        assertSame(expected, parsed);
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL), anyString());
    }

    @Test
    void parse_experimentalParseFailure_noInfosimples_reportsExhaustedAndThrowsExperimentalKey() {
        var generic = genericAdapter("<html>weird layout</html>");
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.parse(fetched));

        assertEquals("receipt.state.experimental_failed", thrown.getMessageKey());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL),
                eq(StateIngestionOutcome.PARSE_FAILED), anyString(), anyString());
        verify(stateCoverage).reportExhausted(eq(UnidadeFederativa.BA), eq(CHAVE_BA), eq(QR_URL_BA),
                anyString(), anyString());
    }

    @Test
    void parse_experimentalParseFailure_infosimplesRescues() {
        var generic = genericAdapter("<html>weird layout</html>");
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage);
        var fetched = new FetchedDocument(generic, "<html>weird layout</html>", CHAVE_BA, UnidadeFederativa.BA, QR_URL_BA);

        var parsed = service.parse(fetched);

        assertSame(preParsed, parsed);
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.INFOSIMPLES), anyString());
    }

    @Test
    void fetch_experimentalFetchFailure_noInfosimples_reportsExhaustedAndThrowsExperimentalKey() {
        var generic = genericAdapter(null); // portal unreachable
        var service = new SefazIngestionService(List.of(generic), Optional.empty(), paidApiGuard, stateCoverage);

        var thrown = assertThrows(ExperimentalStateFailedException.class, () -> service.fetch(QR_URL_BA));

        assertEquals("receipt.state.experimental_failed", thrown.getMessageKey());
        verify(stateCoverage).recordFailure(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.QR_PORTAL),
                eq(StateIngestionOutcome.FETCH_FAILED), anyString(), anyString());
        verify(stateCoverage).reportExhausted(eq(UnidadeFederativa.BA), eq(CHAVE_BA), eq(QR_URL_BA),
                anyString(), any());
    }

    @Test
    void fetch_experimentalFetchFailure_infosimplesRescues() {
        var generic = genericAdapter(null);
        var infosimples = mock(InfosimplesService.class);
        var preParsed = sampleParsed();
        when(infosimples.fetchParsed(eq(CHAVE_BA), eq(UnidadeFederativa.BA))).thenReturn(preParsed);
        var service = new SefazIngestionService(List.of(generic), Optional.of(infosimples), paidApiGuard, stateCoverage);

        var fetched = service.fetch(QR_URL_BA);

        assertSame(preParsed, fetched.preParsed());
        verify(stateCoverage).recordSuccess(eq(UnidadeFederativa.BA), eq(StateIngestionStrategy.INFOSIMPLES), anyString());
    }

    @Test
    void fetch_verifiedStateFailure_neverTouchesStateCoverage() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        when(rsAdapter.fetchHtml(anyString())).thenThrow(new SefazFetchException("RS"));
        var service = new SefazIngestionService(List.of(rsAdapter), Optional.empty(), paidApiGuard, stateCoverage);

        assertThrows(SefazFetchException.class, () -> service.fetch(CHAVE_RS));

        verifyNoInteractions(stateCoverage);
    }

    @Test
    void supportsBareChave_rsAlwaysFalse_signatureStatesNeedInfosimples_nativeStatesTrue() {
        var rsAdapter = mockAdapterFor(UnidadeFederativa.RS);
        var spAdapter = mockAdapterFor(UnidadeFederativa.SP);
        when(spAdapter.requiresQrSignature()).thenReturn(true);
        var msAdapter = mockAdapterFor(UnidadeFederativa.MS);

        var withoutInfosimples = new SefazIngestionService(
                List.of(rsAdapter, spAdapter, msAdapter), Optional.empty(), paidApiGuard, stateCoverage);
        assertTrue(!withoutInfosimples.supportsBareChave(UnidadeFederativa.RS));
        assertTrue(!withoutInfosimples.supportsBareChave(UnidadeFederativa.SP));
        assertTrue(withoutInfosimples.supportsBareChave(UnidadeFederativa.MS));
        assertTrue(!withoutInfosimples.supportsBareChave(UnidadeFederativa.BA));

        var withInfosimples = new SefazIngestionService(
                List.of(rsAdapter, spAdapter, msAdapter), Optional.of(mock(InfosimplesService.class)), paidApiGuard, stateCoverage);
        assertTrue(!withInfosimples.supportsBareChave(UnidadeFederativa.RS));
        assertTrue(withInfosimples.supportsBareChave(UnidadeFederativa.SP));
    }

    @Test
    void fetchedDocument_backwardCompatConstructorSetsNullPreParsed() {
        var adapter = mock(SefazAdapter.class);
        var doc = new FetchedDocument(adapter, "<html/>", CHAVE_RS, UnidadeFederativa.RS, null);

        assertNotNull(doc.adapter());
        assertNull(doc.preParsed());
    }
}
