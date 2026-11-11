package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.model.CostAlert;
import com.relyon.economizaai.model.enums.CostAlertStatus;
import com.relyon.economizaai.repository.CostAlertRepository;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Guaranteed delivery of cost alerts (daily budget per service, provider prepaid balance)
 * to the admin's WhatsApp.
 *
 * <ul>
 *   <li><b>Dedup:</b> {@link #raise} stores at most one {@code cost_alert} row per
 *       (alert, threshold, period) — the table is the memory, so it survives restarts.</li>
 *   <li><b>Delivery:</b> a row stays {@code PENDING} until the provider ACCEPTED the
 *       message. {@link #dispatchPending} retries every few minutes; after a few failed
 *       WhatsApp attempts the same alert also goes by e-mail, and WhatsApp is still
 *       retried a few more times.</li>
 * </ul>
 *
 * <p>No transaction is ever held across the Twilio/SMTP call: the pending rows are read,
 * the send happens untransacted, and the outcome is saved with a plain repository call.
 * Alert sends bypass the Twilio budget/caps on purpose (they are the alarm) but are
 * ledgered by {@link OperationalAlertService}.
 */
@Slf4j
@Service
public class CostAlertService {

    public static final int WARNING_THRESHOLD = 80;
    public static final int LIMIT_THRESHOLD = 100;

    private static final Locale BRAZIL = Locale.forLanguageTag("pt-BR");
    private static final Duration SLOW_RETRY_INTERVAL = Duration.ofMinutes(15);
    private static final Duration RETRY_HORIZON = Duration.ofHours(24);

    private final CostAlertRepository repository;
    private final OperationalAlertService operationalAlertService;
    private final boolean enabled;
    private final int emailFallbackAfterAttempts;
    private final int maxWhatsAppAttempts;
    private final Duration retryInterval;
    private Clock clock = Clock.systemUTC();

    public CostAlertService(
            CostAlertRepository repository,
            OperationalAlertService operationalAlertService,
            @Value("${economizaai.cost-alerts.enabled:true}") boolean enabled,
            @Value("${economizaai.cost-alerts.email-fallback-after-attempts:3}") int emailFallbackAfterAttempts,
            @Value("${economizaai.cost-alerts.max-whatsapp-attempts:8}") int maxWhatsAppAttempts,
            @Value("${economizaai.cost-alerts.retry-interval-ms:120000}") long retryIntervalMs) {
        this.repository = repository;
        this.operationalAlertService = operationalAlertService;
        this.enabled = enabled;
        this.emailFallbackAfterAttempts = emailFallbackAfterAttempts;
        this.maxWhatsAppAttempts = Math.max(maxWhatsAppAttempts, emailFallbackAfterAttempts);
        this.retryInterval = Duration.ofMillis(retryIntervalMs);
    }

    /** Test seam — drive retry timing deterministically. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Which alert threshold a consumption has reached: {@value #LIMIT_THRESHOLD} when the
     * limit is fully consumed, {@value #WARNING_THRESHOLD} from 80% on, 0 below that (or
     * when there is no limit).
     */
    public static int thresholdReached(BigDecimal consumed, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0 || consumed == null) return 0;
        if (consumed.compareTo(limit) >= 0) return LIMIT_THRESHOLD;
        var warningLine = limit.multiply(BigDecimal.valueOf(WARNING_THRESHOLD)).movePointLeft(2);
        return consumed.compareTo(warningLine) >= 0 ? WARNING_THRESHOLD : 0;
    }

    /** "R$ 1.234,50" / "US$ 9,80" — Brazilian formatting for the alert wording. */
    public static String money(String currencySymbol, BigDecimal amount) {
        return currencySymbol + " " + String.format(BRAZIL, "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Registers an alert to be delivered, once per (alert, threshold, period).
     *
     * @return true when this call created it; false when it already existed (or alerts are off)
     */
    public boolean raise(String alertKey, int threshold, String periodKey, String headline, String detail) {
        if (!enabled) return false;
        if (repository.existsByAlertKeyAndThresholdAndPeriodKey(alertKey, threshold, periodKey)) return false;
        try {
            repository.save(CostAlert.builder()
                    .alertKey(alertKey)
                    .threshold(threshold)
                    .periodKey(periodKey)
                    .headline(headline)
                    .detail(detail)
                    .status(CostAlertStatus.PENDING)
                    .nextAttemptAt(OffsetDateTime.now(clock))
                    .build());
        } catch (DataIntegrityViolationException alreadyRaised) {
            return false; // a concurrent check registered the same alert first
        }
        log.info("cost_alert.raised key={} threshold={} period={}", alertKey, threshold, periodKey);
        return true;
    }

    /** Tries to deliver every alert still owed. Synchronized: the monitors also call it right after raising. */
    @Scheduled(fixedDelayString = "${economizaai.cost-alerts.retry-interval-ms:120000}",
            initialDelayString = "${economizaai.cost-alerts.retry-initial-delay-ms:60000}")
    public synchronized void dispatchPending() {
        if (!enabled) return;
        var dueAlerts = repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                CostAlertStatus.PENDING, OffsetDateTime.now(clock));
        for (var alert : dueAlerts) {
            try {
                attemptDelivery(alert);
            } catch (RuntimeException unexpected) {
                log.warn("cost_alert.dispatch_error key={} threshold={} reason={}",
                        alert.getAlertKey(), alert.getThreshold(), unexpected.getClass().getSimpleName());
            }
        }
    }

    private void attemptDelivery(CostAlert alert) {
        var now = OffsetDateTime.now(clock);
        var whatsAppReady = operationalAlertService.isWhatsAppReady();
        if (whatsAppReady && alert.getWhatsappAttempts() < maxWhatsAppAttempts) {
            if (operationalAlertService.sendWhatsAppConfirmed(alert.getHeadline())) {
                alert.setWhatsappSentAt(now);
                finish(alert, CostAlertStatus.DELIVERED);
                return;
            }
            alert.setWhatsappAttempts(alert.getWhatsappAttempts() + 1);
        }
        var whatsAppGivenUp = !whatsAppReady || alert.getWhatsappAttempts() >= maxWhatsAppAttempts;
        var emailFallbackDue = alert.getEmailSentAt() == null
                && (!whatsAppReady || alert.getWhatsappAttempts() >= emailFallbackAfterAttempts);
        if (emailFallbackDue && operationalAlertService.sendEmailConfirmed(alert.getHeadline(), alert.getDetail())) {
            alert.setEmailSentAt(now);
            log.info("cost_alert.email_fallback_sent key={} threshold={}", alert.getAlertKey(), alert.getThreshold());
        }
        if (alert.getEmailSentAt() != null && whatsAppGivenUp) {
            finish(alert, CostAlertStatus.DELIVERED_BY_EMAIL);
            return;
        }
        if (alert.getCreatedAt() != null && alert.getCreatedAt().plus(RETRY_HORIZON).isBefore(now)) {
            log.error("cost_alert.expired key={} threshold={} period={} whatsappAttempts={} — nenhum canal aceitou o aviso",
                    alert.getAlertKey(), alert.getThreshold(), alert.getPeriodKey(), alert.getWhatsappAttempts());
            finish(alert, CostAlertStatus.EXPIRED);
            return;
        }
        alert.setNextAttemptAt(now.plus(whatsAppGivenUp ? SLOW_RETRY_INTERVAL : retryInterval));
        repository.save(alert);
        log.warn("cost_alert.retry_scheduled key={} threshold={} whatsappAttempts={} emailSent={}",
                alert.getAlertKey(), alert.getThreshold(), alert.getWhatsappAttempts(), alert.getEmailSentAt() != null);
    }

    private void finish(CostAlert alert, CostAlertStatus status) {
        alert.setStatus(status);
        repository.save(alert);
        log.info("cost_alert.{} key={} threshold={} period={} whatsappAttempts={}",
                status.name().toLowerCase(Locale.ROOT), alert.getAlertKey(), alert.getThreshold(),
                alert.getPeriodKey(), alert.getWhatsappAttempts());
    }
}
