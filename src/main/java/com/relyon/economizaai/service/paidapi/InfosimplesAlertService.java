package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.service.ContactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin alerts for the Infosimples prepaid account — the owner must KNOW when
 * CE (and the paid fallbacks) stop working, instead of discovering via a user
 * complaint. Two triggers: a failed paid consult (any non-200 — includes the
 * 603 "sem saldo" family) and the daily snapshot finding low/unreachable saldo.
 *
 * <p>Consult alerts are throttled in-memory (single-instance deploy) so a burst
 * of failing receipts sends ONE e-mail, not one per nota. Alert dispatch must
 * never break the ingestion flow — failures are logged and swallowed.
 */
@Slf4j
@Service
public class InfosimplesAlertService {

    private static final Duration CONSULT_ALERT_THROTTLE = Duration.ofHours(6);

    private final ContactService contactService;
    private final BigDecimal lowBalanceThreshold;
    private final AtomicReference<Instant> lastConsultAlert = new AtomicReference<>();

    public InfosimplesAlertService(ContactService contactService,
                                   @Value("${economizaai.infosimples.low-balance-threshold:5.00}") BigDecimal lowBalanceThreshold) {
        this.contactService = contactService;
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    /** Daily snapshot hook: alerts when the saldo crossed below the configured threshold. */
    public void checkLowBalance(BigDecimal saldo) {
        if (saldo == null || saldo.compareTo(lowBalanceThreshold) >= 0) {
            return;
        }
        alertLowBalance(saldo);
    }

    /** A paid NFC-e consult failed (CE or fallback) — throttled to one e-mail per window. */
    public void alertConsultFailure(String uf, int providerCode, String providerMessage) {
        var now = Instant.now();
        var last = lastConsultAlert.get();
        if (last != null && Duration.between(last, now).compareTo(CONSULT_ALERT_THROTTLE) < 0) {
            log.debug("infosimples.alert.throttled uf={} code={}", uf, providerCode);
            return;
        }
        lastConsultAlert.set(now);
        dispatch("Infosimples: consulta NFC-e falhou (uf=" + uf + ")",
                """
                Uma consulta paga na Infosimples falhou agora.

                - UF: %s
                - Código do provedor: %d
                - Mensagem: %s
                - Quando: %s

                Se o código for da família 6xx (ex.: 603), normalmente é conta sem saldo/autorização —
                notas de CE vão continuar falhando até resolver. Saldo ao vivo no painel admin
                (Operações → custos) ou em api.infosimples.com.
                Novos alertas deste tipo ficam suprimidos por %d horas.
                """.formatted(uf, providerCode,
                providerMessage == null ? "(sem mensagem)" : providerMessage,
                OffsetDateTime.now(), CONSULT_ALERT_THROTTLE.toHours()));
    }

    private void alertLowBalance(BigDecimal saldo) {
        dispatch("Infosimples: saldo baixo (R$ " + saldo + ")",
                """
                O snapshot diário encontrou o saldo da Infosimples em R$ %s (limite de alerta: R$ %s).

                Sem saldo, notas de CE param de entrar (única rota) e os fallbacks pagos param de
                socorrer portais fora do ar. Recarga via Pix em api.infosimples.com.
                """.formatted(saldo, lowBalanceThreshold));
    }

    /** Daily snapshot could not read the account at all (API down, token revogado…). */
    public void alertAccountUnreachable() {
        dispatch("Infosimples: conta inacessível no snapshot diário",
                """
                O snapshot diário não conseguiu ler a conta da Infosimples (API fora, token inválido
                ou revogado). Sem visibilidade de saldo, uma falha de CE pode passar despercebida —
                vale conferir api.infosimples.com.
                """);
    }

    private void dispatch(String subject, String body) {
        try {
            contactService.notifyAdmin(subject, body);
            log.info("infosimples.alert.dispatched subject='{}'", subject);
        } catch (RuntimeException dispatchFailure) {
            log.warn("infosimples.alert.dispatch_failed subject='{}' reason={}",
                    subject, dispatchFailure.getClass().getSimpleName());
        }
    }
}
