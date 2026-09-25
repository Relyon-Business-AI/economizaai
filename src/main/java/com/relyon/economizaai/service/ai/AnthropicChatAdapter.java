package com.relyon.economizaai.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Anthropic implementation of {@link AiChatPort}. The client is built lazily on
 * first use (an empty key means the AI layer is disabled — {@link AiGateway}
 * checks that before calling here). Plain text in/out: prompts instruct the
 * model to answer with strict JSON and the callers parse it defensively.
 */
@Slf4j
@Component
public class AnthropicChatAdapter implements AiChatPort {

    @Value("${economizaai.ai.api-key:}")
    private String apiKey;

    @Value("${economizaai.ai.timeout-seconds:60}")
    private int timeoutSeconds;

    private volatile AnthropicClient client;

    boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatResult complete(String model, String systemPrompt, String userPrompt, int maxTokens) {
        var response = clientInstance().messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build());
        var text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", String::concat);
        return new ChatResult(text, response.usage().inputTokens(), response.usage().outputTokens());
    }

    private AnthropicClient clientInstance() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (!isConfigured()) {
                        throw new IllegalStateException("ANTHROPIC_API_KEY not configured");
                    }
                    client = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .build();
                    log.info("ai.client_initialized timeout={}s", timeoutSeconds);
                }
            }
        }
        return client;
    }
}
