package com.relyon.economizaai.service.ai;

/**
 * Thin seam over the LLM provider so every AI feature (and its tests) depends on
 * this interface instead of the vendor SDK. One implementation: Anthropic.
 */
public interface AiChatPort {

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
}
