package com.relyon.economizaai.service.llm;

/** An LLM HTTP call failed or returned an unusable body — callers decide whether to retry, skip, or surface. */
public class LlmCallFailedException extends RuntimeException {

    public LlmCallFailedException(String message) {
        super(message);
    }
}
