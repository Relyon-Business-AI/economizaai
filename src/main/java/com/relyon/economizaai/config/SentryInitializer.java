package com.relyon.economizaai.config;

import io.sentry.Sentry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes the Sentry SDK manually (the Spring Boot starter is incompatible with
 * Boot 4 — its autoconfig needs {@code RestClientCustomizer}, removed in Boot 4). Init
 * is skipped when no DSN is configured, so dev/CI/local runs are a complete no-op and
 * {@code Sentry.captureException(...)} elsewhere does nothing. Set SENTRY_DSN (plus
 * SENTRY_ENVIRONMENT) on the Render service to activate.
 *
 * <p>Errors-only (no performance tracing) to stay in the free tier, and
 * {@code sendDefaultPii=false} for LGPD — we already mask CPF/JWT and must not let
 * Sentry re-capture them from request data.
 */
@Slf4j
@Configuration
public class SentryInitializer {

    @Value("${sentry.dsn:}")
    private String dsn;

    @Value("${sentry.environment:dev}")
    private String environment;

    @Value("${sentry.release:}")
    private String release;

    @PostConstruct
    void init() {
        if (dsn == null || dsn.isBlank()) {
            log.info("sentry.disabled reason=no_dsn");
            return;
        }
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(environment);
            if (release != null && !release.isBlank()) {
                options.setRelease(release);
            }
            options.setTracesSampleRate(0.0);
            options.setSendDefaultPii(false);
        });
        log.info("sentry.enabled environment={}", environment);
    }
}
