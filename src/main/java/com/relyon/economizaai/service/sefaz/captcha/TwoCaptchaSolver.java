package com.relyon.economizaai.service.sefaz.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.CaptchaProperties;
import com.relyon.economizaai.exception.CaptchaSolveFailedException;
import com.relyon.economizaai.exception.CaptchaUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 2Captcha reCAPTCHA-v2 solver — the reference {@link CaptchaSolver}. Active
 * only when {@code economizaai.captcha.provider=twocaptcha}. The 2Captcha HTTP
 * API ({@code in.php} submit → poll {@code res.php}) is also exposed by several
 * compatible services (CapMonster, etc.), so pointing {@code base-url} at one of
 * those reuses this class unchanged.
 *
 * <p>Flow: submit (siteKey, pageUrl) → get a captcha id → poll until the worker
 * returns the token or the {@code timeout-ms} budget is exhausted. The two HTTP
 * calls are isolated as protected seams so the polling/parsing is unit-testable
 * without the network.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "economizaai.captcha", name = "provider", havingValue = "twocaptcha")
public class TwoCaptchaSolver implements CaptchaSolver {

    private static final String NOT_READY = "CAPCHA_NOT_READY"; // 2Captcha's documented (mis)spelling

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final long timeoutMs;
    private final long pollIntervalMs;

    public TwoCaptchaSolver(RestClient.Builder builder, CaptchaProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(30000);
        this.restClient = builder.requestFactory(requestFactory).build();
        var configured = properties.getBaseUrl();
        this.baseUrl = (configured == null || configured.isBlank() ? "https://2captcha.com" : configured)
                .replaceAll("/+$", "");
        this.apiKey = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
        this.timeoutMs = properties.getTimeoutMs();
        this.pollIntervalMs = Math.max(0, properties.getPollIntervalMs());
        log.info("captcha.solver provider=twocaptcha baseUrl={} configured={}", baseUrl, isConfigured());
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public String solveRecaptchaV2(String siteKey, String pageUrl) {
        if (!isConfigured()) {
            throw new CaptchaUnavailableException("captcha");
        }
        var captchaId = submitAndReadId(siteKey, pageUrl);
        log.info("captcha.solve.submitted id={} siteKey={}", captchaId, siteKey);

        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            sleep(pollIntervalMs);
            var token = pollAndReadToken(captchaId);
            if (token != null) {
                log.info("captcha.solve.ok id={} tokenLen={}", captchaId, token.length());
                return token;
            }
        }
        log.warn("captcha.solve.timeout id={} budgetMs={}", captchaId, timeoutMs);
        throw new CaptchaSolveFailedException("timeout");
    }

    private String submitAndReadId(String siteKey, String pageUrl) {
        var url = UriComponentsBuilder.fromUriString(baseUrl + "/in.php")
                .queryParam("key", apiKey)
                .queryParam("method", "userrecaptcha")
                .queryParam("googlekey", siteKey)
                .queryParam("pageurl", pageUrl)
                .queryParam("json", "1")
                .build(true).toUriString();
        var body = submit(url);
        return readField(body, "submit");
    }

    private String pollAndReadToken(String captchaId) {
        var url = UriComponentsBuilder.fromUriString(baseUrl + "/res.php")
                .queryParam("key", apiKey)
                .queryParam("action", "get")
                .queryParam("id", captchaId)
                .queryParam("json", "1")
                .build(true).toUriString();
        var body = poll(url);
        try {
            var json = objectMapper.readTree(body);
            var request = json.path("request").asText("");
            if (json.path("status").asInt() == 1) return request;
            if (NOT_READY.equals(request)) return null; // keep polling
            throw new CaptchaSolveFailedException(request.isBlank() ? "poll-error" : request);
        } catch (CaptchaSolveFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CaptchaSolveFailedException("poll-unparseable");
        }
    }

    /** Reads {@code request} from a {status,request} body, treating status!=1 as a hard failure. */
    private String readField(String body, String phase) {
        try {
            var json = objectMapper.readTree(body);
            if (json.path("status").asInt() == 1) return json.path("request").asText();
            var error = json.path("request").asText("");
            throw new CaptchaSolveFailedException(error.isBlank() ? phase + "-error" : error);
        } catch (CaptchaSolveFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CaptchaSolveFailedException(phase + "-unparseable");
        }
    }

    /** Submit HTTP seam — isolated so the solve flow is unit-testable. */
    protected String submit(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    /** Poll HTTP seam — isolated so the solve flow is unit-testable. */
    protected String poll(String url) {
        return restClient.get().uri(url).retrieve().body(String.class);
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CaptchaSolveFailedException("interrupted");
        }
    }
}
