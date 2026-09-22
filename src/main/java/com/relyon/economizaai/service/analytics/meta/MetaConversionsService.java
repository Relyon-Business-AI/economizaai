package com.relyon.economizaai.service.analytics.meta;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.ratelimit.ClientIpResolver;
import com.relyon.economizaai.service.privacy.LogMasker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Reports each new signup to the Meta Conversions API (server-side), so the
 * conversion survives ad-blockers / iOS ITP and is attributed to the campaign via
 * the ad-click ({@code fbc}) and hashed email. Advisory: never blocks or fails a
 * registration — request-context data (IP, user-agent) is captured on the request
 * thread, then the HTTP send runs on the async pool.
 *
 * <p>INERT until {@link MetaConversionsProperties#isConfigured()} (Pixel id + CAPI token).
 */
@Slf4j
@Service
public class MetaConversionsService {

    private final MetaConversionsClient client;
    private final Executor executor;
    private final MetaConversionsProperties properties;

    public MetaConversionsService(MetaConversionsClient client,
                                  @Qualifier(AsyncConfig.RECEIPT_INGEST_EXECUTOR) Executor executor,
                                  MetaConversionsProperties properties) {
        this.client = client;
        this.executor = executor;
        this.properties = properties;
    }

    /** {@code method}: e-mail / google / apple. */
    public void reportCompleteRegistration(User user, String method) {
        if (!properties.isConfigured()) {
            return;
        }
        // Captured HERE, on the request thread — the async pool has no request bound.
        var clientIp = currentRequest().map(ClientIpResolver::resolve).orElse(null);
        var userAgent = currentRequest().map(request -> request.getHeader("User-Agent")).orElse(null);
        // Stable per-user id so retries dedupe. Send as a "website" event: the ad-click
        // (fbc) originates on the landing; app-native app_data can be added once the app SDK ships.
        var eventId = user.getId() + "-completeregistration";
        var email = user.getEmail();
        var fbclid = user.getAttributionClickId();
        var eventTime = OffsetDateTime.now().toEpochSecond();
        try {
            executor.execute(() -> client.sendCompleteRegistration(
                    eventId, email, fbclid, clientIp, userAgent, method, true, eventTime));
            log.info("meta.capi.dispatched user={} method={}", LogMasker.email(email), method);
        } catch (RejectedExecutionException ex) {
            log.warn("meta.capi.dispatch_rejected user={}", LogMasker.email(email));
        }
    }

    /** Empty when called outside a request (tests, schedulers). */
    private Optional<HttpServletRequest> currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? Optional.of(attributes.getRequest())
                : Optional.empty();
    }
}
