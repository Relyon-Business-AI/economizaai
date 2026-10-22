package com.relyon.economizaai.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

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
        var params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .addUserMessage(userPrompt);
        // System vira um bloco com cache_control: o prefixo estável (ex.: gabarito da taxonomia) é
        // cacheado pela Anthropic. Se ficar abaixo do mínimo cacheável, o provedor só ignora (sem erro).
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            params.systemOfTextBlockParams(List.of(TextBlockParam.builder()
                    .text(systemPrompt)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()));
        }
        var response = clientInstance().messages().create(params.build());
        var text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", String::concat);
        var usage = response.usage();
        return new ChatResult(text, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L));
    }

    @Override
    public ChatResult completeWithImage(String model, String systemPrompt, String userPrompt,
                                        byte[] imageBytes, String imageMediaType, int maxTokens) {
        var mediaType = switch (imageMediaType == null ? "" : imageMediaType.toLowerCase()) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
        var imageBlock = ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(mediaType)
                        .data(Base64.getEncoder().encodeToString(imageBytes))
                        .build())
                .build();
        var params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(imageBlock),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(userPrompt).build())));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            params.systemOfTextBlockParams(List.of(TextBlockParam.builder()
                    .text(systemPrompt)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()));
        }
        var response = clientInstance().messages().create(params.build());
        var text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", String::concat);
        var usage = response.usage();
        return new ChatResult(text, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L));
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
