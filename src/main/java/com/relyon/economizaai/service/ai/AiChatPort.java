package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.enums.AiProvider;

/**
 * Thin seam over the LLM provider so every AI feature (and its tests) depends on
 * this interface instead of the vendor SDK. Implementações: Anthropic e OpenAI,
 * selecionáveis por tier em runtime (ver {@code AiProviderSettingsService}).
 */
public interface AiChatPort {

    /** Qual provedor este adapter atende — usado pelo gateway pra rotear por tier. */
    AiProvider provider();

    /** True quando a credencial do provedor está configurada. */
    boolean isConfigured();

    ChatResult complete(String model, String systemPrompt, String userPrompt, int maxTokens);

    /**
     * cacheWriteTokens/cacheReadTokens = prompt-caching accounting (0 when caching didn't apply —
     * e.g. prefix below the provider's minimum, or cache miss). {@code inputTokens} is the FRESH
     * (non-cached) input only, as the provider reports it.
     */
    record ChatResult(String text, long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {
        public ChatResult(String text, long inputTokens, long outputTokens) {
            this(text, inputTokens, outputTokens, 0, 0);
        }
    }

    /** Multimodal: one user turn with an image + text. Adapters without vision support throw. */
    default ChatResult completeWithImage(String model, String systemPrompt, String userPrompt,
                                         byte[] imageBytes, String imageMediaType, int maxTokens) {
        throw new UnsupportedOperationException("vision not supported by this adapter");
    }
}
