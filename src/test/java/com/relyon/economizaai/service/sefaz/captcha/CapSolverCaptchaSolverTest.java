package com.relyon.economizaai.service.sefaz.captcha;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapSolverCaptchaSolverTest {

    @Test
    void solveCloudflareTurnstile_usesTurnstileTaskAndReturnsToken() {
        var solver = solver(List.of(
                Map.of("errorId", 0, "taskId", "task-1"),
                Map.of("errorId", 0, "status", "ready", "solution", Map.of("token", "turnstile-token"))
        ));

        var token = solver.solveCloudflareTurnstile("0x4AAAA", "https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc");

        assertEquals("turnstile-token", token);
        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) solver.bodies.get(0).get("task");
        assertEquals("AntiTurnstileTaskProxyLess", task.get("type"));
        assertEquals("0x4AAAA", task.get("websiteKey"));
        assertEquals("https://sat.sef.sc.gov.br/tax.NET/SecurityVerify.aspx?rq=abc", task.get("websiteURL"));
    }

    @Test
    void solveRecaptchaV2_keepsRecaptchaTaskAndSolutionField() {
        var solver = solver(List.of(
                Map.of("errorId", 0, "taskId", "task-2"),
                Map.of("errorId", 0, "status", "ready", "solution", Map.of("gRecaptchaResponse", "recaptcha-token"))
        ));

        var token = solver.solveRecaptchaV2("site-key", "https://www.dfe.ms.gov.br/nfce/consulta/");

        assertEquals("recaptcha-token", token);
        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) solver.bodies.get(0).get("task");
        assertEquals("ReCaptchaV2TaskProxyless", task.get("type"));
    }

    private TestCapSolver solver(List<Map<String, Object>> responses) {
        return new TestCapSolver(responses);
    }

    private static final class TestCapSolver extends CapSolverCaptchaSolver {
        private final ArrayDeque<Map<String, Object>> responses;
        private final List<Map<String, Object>> bodies = new ArrayList<>();

        TestCapSolver(List<Map<String, Object>> responses) {
            super(RestClient.builder(), "api-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        protected Map<String, Object> post(String path, Object body) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) body;
            bodies.add(typed);
            return responses.removeFirst();
        }

        @Override
        protected void sleep(int ms) {
            // no-op in unit tests
        }
    }
}
