package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.notifications.OperationalAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sonda, a cada 6h, estados "pausados" no Infosimples (origem/portal da SEFAZ fora) e avisa o
 * dono quando VOLTAM — pra então onboardar o estado. Nasceu pro SE: funcionou 100% em meados de
 * out/2026, o portal caiu ~22/10 e o Infosimples pausou a consulta (retorna code 615). Quando
 * {@code sefaz/se/nfce} voltar a responder 200, o alerta dispara UMA vez; o onboarding em si
 * (limpar o cap de evidência, revalidar a nota real em dev, add ao teste de estados, subir pra
 * prod) é feito por uma pessoa/sessão a partir do alerta — o probe só detecta a volta.
 *
 * <p>Usa {@link InfosimplesService#probeRouteCode} (chamada leve, sem alerta/circuit-breaker).
 * Enquanto o estado está fora o code é 615/600/-1 e nada acontece; sondar nesse estado não é
 * cobrado. Após detectar a volta, para de sondar aquela UF (não gasta consulta à toa).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "economizaai.infosimples", name = "enabled", havingValue = "true")
public class PausedStateRecoveryProbe {

    private final InfosimplesService infosimples;
    private final OperationalAlertService alertService;
    private final ReceiptRepository receiptRepository;
    private final GenericQrPortalAdapter qrPortalAdapter;
    private final Set<UnidadeFederativa> watchedUfs;
    // UFs já detectadas de volta (e já alertadas) — para de sondar até o próximo restart.
    private final Set<UnidadeFederativa> alreadyRecovered = ConcurrentHashMap.newKeySet();

    public PausedStateRecoveryProbe(InfosimplesService infosimples, OperationalAlertService alertService,
                                    ReceiptRepository receiptRepository, GenericQrPortalAdapter qrPortalAdapter,
                                    @Value("${economizaai.ingestion.paused-state-probe.ufs:SE}") String ufsCsv) {
        this.infosimples = infosimples;
        this.alertService = alertService;
        this.receiptRepository = receiptRepository;
        this.qrPortalAdapter = qrPortalAdapter;
        this.watchedUfs = parseUfs(ufsCsv);
        log.info("paused_state.probe watched={}", watchedUfs);
    }

    @Scheduled(
            fixedDelayString = "${economizaai.ingestion.paused-state-probe-delay-ms:21600000}",
            initialDelayString = "${economizaai.ingestion.paused-state-probe-initial-delay-ms:60000}")
    public void probe() {
        for (var uf : watchedUfs) {
            if (alreadyRecovered.contains(uf)) continue;
            var chaves = receiptRepository.chavesByUf(uf, PageRequest.of(0, 1));
            if (chaves.isEmpty()) {
                log.debug("paused_state.no_sample uf={}", uf);
                continue;
            }
            var code = infosimples.probeRouteCode(chaves.get(0), uf);
            var via = code == 200 ? "Infosimples (code 200)" : nativePortalResolves(uf) ? "portal nativo (QR válido com itens)" : null;
            if (via != null) {
                alreadyRecovered.add(uf);
                log.info("paused_state.recovered uf={} via={}", uf, via);
                alertService.alertAdmin(
                        "SEFAZ " + uf + " voltou (" + via + ") — pronto pra onboardar",
                        "A consulta de " + uf.name().toLowerCase() + "/nfce voltou via " + via + ". Próximo passo: "
                                + "limpar o cap de evidência do " + uf + ", revalidar a nota real em dev, "
                                + "adicionar ao teste de estados e subir pra prod.");
            } else {
                log.debug("paused_state.still_down uf={} infosimplesCode={}", uf, code);
            }
        }
    }

    /**
     * Sinal de recuperação pela via do app (scan ao vivo): bate no portal NATIVO com um QR real
     * guardado e só considera de volta quando volta a DANFE com itens — não mais a página de "QR
     * inválido" que o portal passou a devolver após a queda (ver status de SE). Reusa o cliente do
     * {@link GenericQrPortalAdapter}, que já lida com redirect/TLS/encoding do portal. Qualquer falha
     * (exceção, inválido, sem itens) = ainda fora. Nota: os estados vigiados (default SE) não têm
     * captcha — não gasta solve pago; não adicionar UF com captcha ao watch-list por isso.
     */
    private boolean nativePortalResolves(UnidadeFederativa uf) {
        var qrs = receiptRepository.qrPayloadsByUf(uf, PageRequest.of(0, 1));
        if (qrs.isEmpty()) return false;
        try {
            var html = qrPortalAdapter.fetchHtml(qrs.get(0));
            if (html == null) return false;
            var invalid = html.toUpperCase(Locale.ROOT).contains("INVÁLIDO")
                    || html.toUpperCase(Locale.ROOT).contains("INVALIDO");
            var hasItems = html.contains("tabResult") || html.contains("txtTit");
            return hasItems && !invalid;
        } catch (RuntimeException ex) {
            log.debug("paused_state.native_still_down uf={} reason={}", uf, ex.getClass().getSimpleName());
            return false;
        }
    }

    private static Set<UnidadeFederativa> parseUfs(String csv) {
        var ufs = EnumSet.noneOf(UnidadeFederativa.class);
        if (csv == null || csv.isBlank()) return ufs;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    try {
                        ufs.add(UnidadeFederativa.valueOf(token.toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        log.warn("paused_state.probe.ufs.ignored token='{}'", token);
                    }
                });
        return ufs;
    }
}
