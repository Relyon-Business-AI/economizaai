package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One real NFC-e per SUPPORTED state, parsed through that state's parser, asserting
 * it still yields a usable receipt (items + a positive total). This is the safety net
 * guarding against any change (generic captcha/fetch tweaks, routing, parser edits)
 * silently dropping support for a state we already validated.
 *
 * <p>When a new state gets a verified adapter, ADD a row here with a real fixture —
 * a missing state is the signal that its regression coverage is missing. Exact item
 * counts/totals live in each per-adapter test; this one only asserts "still parses".
 *
 * <p>RJ is intentionally absent: it's served by Infosimples ({@code nfce-completa}
 * JSON → ParsedReceipt, not an HTML parser), a different mechanism covered by
 * {@link InfosimplesBackedAdapterTest} and untouched by the generic-chain changes.
 */
class SupportedStatesRegressionTest {

    @FunctionalInterface
    interface DanfeParser {
        ParsedReceipt parse(String content, String chaveAcesso, String sourceUrl);
    }

    private record StateCase(String uf, String fixture, String chave, DanfeParser parser) {
        @Override
        public String toString() {
            return uf;
        }
    }

    static Stream<StateCase> supportedStates() {
        return Stream.of(
                new StateCase("DF", "df/marcosbko-atacadista.html",
                        "53261007738069000328653050000125721251741713", DfNfceDanfeParser::parse),
                // GO embeds the DANFE as a JS-escaped string in the render page; the
                // adapter extracts it before delegating to the shared SC parser.
                new StateCase("GO", "go/nfce-render-page-with-embedded-danfe.html",
                        "52260793209765049205655290000050451048579174",
                        (content, chave, url) -> ScNfceDanfeParser.parse(
                                GoiasNfcePortalAdapter.extractEmbeddedDanfe(content), chave, url)),
                new StateCase("MG", "mg/danfe.html",
                        "31260911614938000118650180003378659268089479", MgNfceDanfeParser::parse),
                new StateCase("MS", "ms/nfce-real-cvale.html",
                        "50260777863223012709650180004455861342485537", ResponsiveDanfeParser::parse),
                new StateCase("PE", "pe/pe-mcdonalds-3items.xml",
                        "26260942591651264205650010000777891671062850", NfceXmlParser::parse),
                new StateCase("PR", "pr/nfce-real-raiadrogasil.html",
                        "41260361585865261893650030000564031777660148", ResponsiveDanfeParser::parse),
                new StateCase("RS", "rs/nfce-real-zaffari.html",
                        "43260735254422000178650090001371069852732246", ResponsiveDanfeParser::parse),
                new StateCase("SC", "sc/danfe.html",
                        "23260723301562000170650110000433671111560131", ScNfceDanfeParser::parse),
                new StateCase("SP", "sp/nfce-real-mercadao.html",
                        "35260716881767001421650010000620781001241617", ResponsiveDanfeParser::parse));
    }

    @ParameterizedTest(name = "{0} still parses")
    @MethodSource("supportedStates")
    void eachSupportedStateStillParses(StateCase state) throws Exception {
        var content = new ClassPathResource("fixtures/sefaz/" + state.fixture())
                .getContentAsString(StandardCharsets.UTF_8);

        var parsed = state.parser().parse(content, state.chave(), "https://test/" + state.uf());

        assertTrue(parsed.items().size() > 0, state.uf() + ": parser returned no items");
        assertNotNull(parsed.totalAmount(), state.uf() + ": parser returned no total");
        assertTrue(parsed.totalAmount().signum() > 0, state.uf() + ": total is not positive");
        assertNotNull(parsed.chaveAcesso(), state.uf() + ": chave not set");
    }
}
