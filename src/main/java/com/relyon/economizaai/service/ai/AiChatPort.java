package com.relyon.economizaai.service.ai;

/**
 * Thin seam over the LLM provider so every AI feature (and its tests) depends on
 * this interface instead of the vendor SDK. One implementation: Anthropic.
 */
public interface AiChatPort {

    ChatResult complete(String model, String systemPrompt, String userPrompt, int maxTokens);

    record ChatResult(String text, long inputTokens, long outputTokens) {}
}
