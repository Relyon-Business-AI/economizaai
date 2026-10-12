package com.relyon.economizaai.service.llm;

import com.relyon.economizaai.repository.AiUsageLogRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.ai.AnthropicChatAdapter;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.CpfMasker;
import com.relyon.economizaai.service.sefaz.GoiasNfcePortalAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * REAL-AI validation of the parse-rescue layer: one REAL receipt per covered
 * state, extracted by the actual Anthropic extractor model (the app's live AI
 * stack) and checked against the ground truth the regular parsers assert in the
 * Real*FixtureTest suite. This answers, per UF: "would the rescue have saved
 * this nota if its parser broke?"
 *
 * <p>COSTS MONEY (~R$0.02-0.05/state/run) and needs the network, so it is gated
 * on BOTH env vars and never runs in CI:
 * {@code LLM_RESCUE_REAL_TEST=true ANTHROPIC_API_KEY=sk-... ./mvnw test -Dtest=HtmlReceiptRescueRealLlmTest}
 */
@EnabledIfEnvironmentVariable(named = "LLM_RESCUE_REAL_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class HtmlReceiptRescueRealLlmTest {

    private final HtmlReceiptRescueService service = buildServiceOnRealAiStack();

    /** Guard is a no-op mock: this harness measures extraction quality, not metering (unit-tested elsewhere). */
    private static HtmlReceiptRescueService buildServiceOnRealAiStack() {
        var chatAdapter = new AnthropicChatAdapter();
        ReflectionTestUtils.setField(chatAdapter, "apiKey", System.getenv("ANTHROPIC_API_KEY"));
        ReflectionTestUtils.setField(chatAdapter, "timeoutSeconds", 90);
        var aiGateway = new AiGateway(chatAdapter, mock(AiUsageLogRepository.class));
        ReflectionTestUtils.setField(aiGateway, "extractorModel", "claude-haiku-4-5");
        ReflectionTestUtils.setField(aiGateway, "dailyRequestCap", 1000);
        return new HtmlReceiptRescueService(aiGateway, mock(PaidApiGuardService.class), true, 80, 60000);
    }

    @DisplayName("resgate IA extrai a nota real do estado")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RS, rs/nfce-real-zaffari.html,      43260493015006005182651130003394021410514546, 298.22, 15",
            "PR, pr/nfce-real-raiadrogasil.html, 41260361585865261893650030000564031777660148, 28.97,  3",
            "SP, sp/nfce-real-mercadao.html,     35260716881767001421650010000620781001241617, 158.12, 20",
            "SC, sc/danfe.html,                  42260982647165000548651140000413341798864714, 103.00, 8",
            "MS, ms/nfce-real-cvale.html,        50260677863223012709650190004048511190344086, 61.79,  9",
            "RJ, rj/nfce-real-boutique.html,     33240744005617000175650010000000031892682790, 65.00,  1",
            "GO, go/nfce-render-page-with-embedded-danfe.html, 52260739346861022483650190000191021190388509, 94.14, 6",
            "MG, mg/danfe.html,                  31260911614938000118650180003378659268089479, 73.45,  6",
            "DF, df/marcosbko-atacadista.html,   53261007738069000328653050000125721251741713, 235.21, 50",
            "PE, pe/pe-novo-israel-4items.xml,   26260920534381000287651020001738551001743421, 24.13,  4",
    })
    void rescue_extractsRealStateReceipt(String stateLabel, String fixture, String chave,
                                         String expectedTotal, int expectedItems) throws Exception {
        var html = CpfMasker.strip(new String(
                new ClassPathResource("fixtures/sefaz/" + fixture).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
        if (fixture.startsWith("go/")) {
            // In production GO's fetchHtml unwraps the DANFE embedded in the render
            // page before anything parses it — feed the rescue the same input.
            html = ReflectionTestUtils.invokeMethod(GoiasNfcePortalAdapter.class, "extractEmbeddedDanfe", html);
        }
        var uf = ChaveAcessoParser.extractUf(chave);

        var parsed = service.rescue(html, chave, uf, null, null);

        assertNotNull(parsed, stateLabel + ": rescue returned null (rejected or extraction failed)");
        assertEquals(chave, parsed.chaveAcesso(), stateLabel);
        assertEquals(ChaveAcessoParser.extractCnpj(chave), parsed.cnpjEmitente(), stateLabel);
        assertEquals(expectedItems, parsed.items().size(), stateLabel + ": item count");
        assertEquals(0, new BigDecimal(expectedTotal).compareTo(parsed.totalAmount()),
                stateLabel + ": total was " + parsed.totalAmount());
    }
}
