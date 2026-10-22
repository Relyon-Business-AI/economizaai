package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relyon.economizaai.model.enums.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * OpenAI (ou API OpenAI-compatível) implementation of {@link AiChatPort}. Fala o
 * endpoint Chat Completions ({@code /chat/completions}), texto e visão (imagem via
 * data-URI base64). Prompt-driven JSON como o adapter Anthropic (sem forçar
 * response_format, pra manter compatibilidade entre modelos GPT/o-series).
 *
 * <p>Inerte até {@code OPENAI_API_KEY} ser setada — {@link #isConfigured()} é
 * false e o {@link AiGateway} nunca roteia pra cá. base-url e timeout são
 * config-swappable (provedores OpenAI-compatíveis: Azure, OpenRouter, etc).
 */
@Slf4j
@Component
public class OpenAiChatAdapter implements AiChatPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    public OpenAiChatAdapter(RestClient.Builder builder,
                             @Value("${economizaai.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
                             @Value("${economizaai.openai.base-url:${LLM_BASE_URL:https://api.openai.com/v1}}") String baseUrl,
                             @Value("${economizaai.openai.timeout-ms:60000}") int timeoutMs) {
        this.apiKey = apiKey;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AiProvider provider() {
        return AiProvider.OPENAI;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatResult complete(String model, String systemPrompt, String userPrompt, int maxTokens) {
        var body = baseRequest(model, systemPrompt, maxTokens);
        body.withArray("messages").add(textMessage("user", userPrompt));
        return parse(post(body));
    }

    @Override
    public ChatResult completeWithImage(String model, String systemPrompt, String userPrompt,
                                        byte[] imageBytes, String imageMediaType, int maxTokens) {
        var body = baseRequest(model, systemPrompt, maxTokens);
        var userMessage = objectMapper.createObjectNode().put("role", "user");
        ArrayNode parts = userMessage.withArray("content");
        parts.add(objectMapper.createObjectNode().put("type", "text").put("text", userPrompt));
        var imagePart = objectMapper.createObjectNode().put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", "data:" + imageMediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes));
        parts.add(imagePart);
        body.withArray("messages").add(userMessage);
        return parse(post(body));
    }

    private ObjectNode baseRequest(String model, String systemPrompt, int maxTokens) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        // max_completion_tokens é o parâmetro atual (max_tokens está deprecado e é
        // rejeitado por modelos o-series/GPT-5). Prompt-driven JSON, sem response_format.
        body.put("max_completion_tokens", maxTokens);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.withArray("messages").add(textMessage("system", systemPrompt));
        }
        return body;
    }

    private ObjectNode textMessage(String role, String content) {
        return objectMapper.createObjectNode().put("role", role).put("content", content);
    }

    private ChatResult parse(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            var text = root.path("choices").path(0).path("message").path("content").asText("");
            var usage = root.path("usage");
            var inputTokens = usage.path("prompt_tokens").asLong(0);
            var outputTokens = usage.path("completion_tokens").asLong(0);
            var cachedInput = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
            // OpenAI reporta prompt_tokens incluindo os cacheados; separamos como o
            // ChatResult espera (inputTokens = FRESH, cacheRead = cacheado).
            return new ChatResult(text, inputTokens - cachedInput, outputTokens, 0, cachedInput);
        } catch (Exception ex) {
            throw new AiGateway.AiUnavailableException("OpenAI: resposta ilegível — " + ex.getMessage());
        }
    }

    /** Raw HTTP — seam pra teste sem rede (sobrescrito nos unit tests). */
    protected String post(ObjectNode body) {
        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
    }
}
