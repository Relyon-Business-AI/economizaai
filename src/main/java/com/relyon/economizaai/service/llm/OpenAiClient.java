package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * Thin OpenAI chat-completions client for the LLM layers (enrichment, auditor,
 * photo extraction). Chosen for cost (gpt-4o-mini class); the provider is
 * config-swappable via base-url/model. Always requests a JSON object response
 * so callers parse structured output, never prose.
 *
 * <p>Every caller must meter its usage through {@code PaidApiGuardService}
 * (LLM_ENRICH / LLM_VISION) — this client only talks HTTP.
 */
@Slf4j
@Service
public class OpenAiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public OpenAiClient(RestClient.Builder builder,
                        @Value("${economizaai.llm.api-key:}") String apiKey,
                        @Value("${economizaai.llm.base-url:https://api.openai.com/v1}") String baseUrl,
                        @Value("${economizaai.llm.model:gpt-4o-mini}") String model,
                        @Value("${economizaai.llm.timeout-ms:45000}") int timeoutMs) {
        this.apiKey = apiKey;
        this.model = model;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 10000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Text-only structured call. Returns the model's JSON object as a tree. */
    public JsonNode completeJson(String systemPrompt, String userContent, int maxTokens) {
        var body = baseRequest(systemPrompt, maxTokens);
        body.withArray("messages").add(textMessage("user", userContent));
        return parseContent(post(body));
    }

    /** Vision structured call: one image plus an instruction, JSON object out. */
    public JsonNode completeJsonWithImage(String systemPrompt, String userText,
                                          byte[] imageBytes, String imageMediaType, int maxTokens) {
        var body = baseRequest(systemPrompt, maxTokens);
        var userMessage = objectMapper.createObjectNode().put("role", "user");
        ArrayNode parts = userMessage.withArray("content");
        parts.add(objectMapper.createObjectNode().put("type", "text").put("text", userText));
        var imagePart = objectMapper.createObjectNode().put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", "data:" + imageMediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes));
        parts.add(imagePart);
        body.withArray("messages").add(userMessage);
        return parseContent(post(body));
    }

    private ObjectNode baseRequest(String systemPrompt, int maxTokens) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.putObject("response_format").put("type", "json_object");
        body.withArray("messages").add(textMessage("system", systemPrompt));
        return body;
    }

    private ObjectNode textMessage(String role, String content) {
        return objectMapper.createObjectNode().put("role", role).put("content", content);
    }

    private JsonNode parseContent(String responseBody) {
        try {
            var content = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content").asText();
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new LlmCallFailedException("unparseable response: " + ex.getMessage());
        }
    }

    /** Raw HTTP call — isolated as a seam so callers are unit-testable without the network. */
    protected String post(ObjectNode body) {
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            throw new LlmCallFailedException(ex.getClass().getSimpleName() + ": " + safeMessage(ex));
        }
    }

    private static String safeMessage(RuntimeException ex) {
        var message = ex.getMessage();
        return message == null ? "" : message.length() > 160 ? message.substring(0, 160) : message;
    }
}
