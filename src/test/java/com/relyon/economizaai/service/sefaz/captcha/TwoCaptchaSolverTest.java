package com.relyon.economizaai.service.sefaz.captcha;

import com.relyon.economizaai.config.CaptchaProperties;
import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoCaptchaSolverTest {

    private CaptchaProperties props(String apiKey) {
        var properties = new CaptchaProperties();
        properties.setProvider("twocaptcha");
        properties.setApiKey(apiKey);
        properties.setPollIntervalMs(0);   // no real waiting in tests
        properties.setTimeoutMs(60000);
        return properties;
    }

    /** Solver whose two HTTP seams are driven by canned response queues. */
    private TwoCaptchaSolver solver(String apiKey, String submitResponse, Deque<String> pollResponses) {
        return new TwoCaptchaSolver(RestClient.builder(), props(apiKey)) {
            @Override protected String submit(String url) {
                return submitResponse;
            }
            @Override protected String poll(String url) {
                return pollResponses.poll();
            }
        };
    }

    @Test
    void isConfigured_falseWhenApiKeyBlank() {
        var solver = new TwoCaptchaSolver(RestClient.builder(), props("  "));
        assertFalse(solver.isConfigured());
        assertThrows(CaptchaUnavailableException.class,
                () -> solver.solveRecaptchaV2("sitekey", "https://page"));
    }

    @Test
    void solve_returnsTokenAfterPolling() {
        var polls = new ArrayDeque<>(List.of(
                "{\"status\":0,\"request\":\"CAPCHA_NOT_READY\"}",
                "{\"status\":0,\"request\":\"CAPCHA_NOT_READY\"}",
                "{\"status\":1,\"request\":\"03AGdBq2-the-token\"}"));
        var solver = solver("key123", "{\"status\":1,\"request\":\"2122988149\"}", polls);

        var token = solver.solveRecaptchaV2("6Ld92rYU", "https://www.dfe.ms.gov.br/nfce/consulta/");

        assertEquals("03AGdBq2-the-token", token);
    }

    @Test
    void solve_submitErrorThrows() {
        var solver = solver("key123", "{\"status\":0,\"request\":\"ERROR_WRONG_USER_KEY\"}", new ArrayDeque<>());

        var ex = assertThrows(CaptchaSolveFailedException.class,
                () -> solver.solveRecaptchaV2("sitekey", "https://page"));
        assertTrue(ex.getMessageKey().contains("captcha"));
    }

    @Test
    void solve_pollErrorThrows() {
        var polls = new ArrayDeque<>(List.of("{\"status\":0,\"request\":\"ERROR_CAPTCHA_UNSOLVABLE\"}"));
        var solver = solver("key123", "{\"status\":1,\"request\":\"42\"}", polls);

        assertThrows(CaptchaSolveFailedException.class,
                () -> solver.solveRecaptchaV2("sitekey", "https://page"));
    }

    @Test
    void solve_timesOutWhenNeverReady() {
        // budget is tiny; every poll says not-ready → deadline passes → failure
        var properties = props("key123");
        properties.setTimeoutMs(1);
        var solver = new TwoCaptchaSolver(RestClient.builder(), properties) {
            @Override protected String submit(String url) { return "{\"status\":1,\"request\":\"7\"}"; }
            @Override protected String poll(String url) { return "{\"status\":0,\"request\":\"CAPCHA_NOT_READY\"}"; }
        };

        var ex = assertThrows(CaptchaSolveFailedException.class,
                () -> solver.solveRecaptchaV2("sitekey", "https://page"));
        assertTrue(ex.getArguments().length > 0);
    }
}
