package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.ratelimit.ClientIpResolver;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.privacy.LogMasker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Emails the admin inbox (contact recipient) whenever a new account is created,
 * so signups from the ads campaign are visible without watching logs. Advisory
 * only: it never blocks or fails a registration — the SMTP send runs on the
 * async pool, and a full pool just drops the alert with a warn.
 *
 * <p>Kill switch: {@code SIGNUP_ALERT_ENABLED=false} (no deploy needed).
 */
@Slf4j
@Service
public class SignupAlertService {

    private final ContactService contactService;
    private final Executor executor;
    private final boolean enabled;

    public SignupAlertService(ContactService contactService,
                              @Qualifier(AsyncConfig.RECEIPT_INGEST_EXECUTOR) Executor executor,
                              @Value("${economizaai.signup-alert.enabled:true}") boolean enabled) {
        this.contactService = contactService;
        this.executor = executor;
        this.enabled = enabled;
    }

    /**
     * Request-context data (IP, Cloudflare country) is captured HERE, on the
     * request thread — the async pool has no request bound to it.
     */
    public void notifyNewAccount(User user, String signupMethod) {
        if (!enabled) {
            return;
        }
        var clientIp = currentRequest().map(ClientIpResolver::resolve).orElse(null);
        var country = currentRequest().map(request -> request.getHeader("CF-IPCountry"))
                .filter(header -> !header.isBlank()).map(String::trim).orElse(null);
        var subject = "Nova conta: " + user.getEmail();
        var body = """
                Nova conta criada.

                - Nome: %s
                - E-mail: %s
                - Método: %s
                - Plataforma: %s
                - Idioma: %s
                - IP: %s
                - País (Cloudflare): %s
                - Quando: %s
                """.formatted(user.getName(), user.getEmail(), signupMethod,
                user.getRegistrationPlatform() == null ? "(não informada)" : user.getRegistrationPlatform(),
                user.getLocale() == null ? "(não informado)" : user.getLocale(),
                clientIp == null ? "(indisponível)" : clientIp,
                country == null ? "(indisponível)" : country,
                OffsetDateTime.now());
        try {
            executor.execute(() -> contactService.notifyAdmin(subject, body));
            log.info("signup_alert.dispatched user={} method={}", LogMasker.email(user.getEmail()), signupMethod);
        } catch (RejectedExecutionException ex) {
            log.warn("signup_alert.dispatch_rejected user={}", LogMasker.email(user.getEmail()));
        }
    }

    /** Empty when called outside a request (tests, schedulers). */
    private Optional<HttpServletRequest> currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? Optional.of(attributes.getRequest())
                : Optional.empty();
    }
}
