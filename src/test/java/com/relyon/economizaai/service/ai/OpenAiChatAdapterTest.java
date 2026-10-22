package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relyon.economizaai.model.enums.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatAdapterTest {

    /** Adapter com post() canned — não toca a rede. */
    private OpenAiChatAdapter adapterReturning(String cannedJson, String apiKey) {
        return new OpenAiChatAdapter(RestClient.builder(), apiKey, "https://api.openai.com/v1", 1000) {
            @Override
            protected String post(ObjectNode body) {
                return cannedJson;
            }
        };
    }

    @Test
    void provider_isOpenAi() {
        assertEquals(AiProvider.OPENAI, adapterReturning("{}", "sk-x").provider());
    }

    @Test
    void isConfigured_reflectsKeyPresence() {
        assertTrue(adapterReturning("{}", "sk-x").isConfigured());
        assertFalse(adapterReturning("{}", "").isConfigured());
        assertFalse(adapterReturning("{}", null).isConfigured());
    }

    @Test
    void complete_parsesTextAndSeparatesCachedInput() {
        var response = """
                {"choices":[{"message":{"content":"{\\"ok\\":true}"}}],
                 "usage":{"prompt_tokens":1000,"completion_tokens":200,
                          "prompt_tokens_details":{"cached_tokens":600}}}""";
        var result = adapterReturning(response, "sk-x").complete("gpt-5-mini", "sys", "user", 500);

        assertEquals("{\"ok\":true}", result.text());
        assertEquals(400, result.inputTokens());   // 1000 - 600 cacheados
        assertEquals(200, result.outputTokens());
        assertEquals(600, result.cacheReadTokens());
    }

    @Test
    void completeWithImage_sendsImagePartAndParses() {
        var response = """
                {"choices":[{"message":{"content":"{\\"cor\\":\\"vermelho\\"}"}}],
                 "usage":{"prompt_tokens":50,"completion_tokens":10}}""";
        var result = adapterReturning(response, "sk-x")
                .completeWithImage("gpt-4o-mini", "sys", "qual cor?", new byte[]{1, 2, 3}, "image/png", 100);

        assertTrue(result.text().contains("vermelho"));
        assertEquals(50, result.inputTokens());
        assertEquals(10, result.outputTokens());
    }
}
