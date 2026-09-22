package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Adapter for states we support <b>only</b> through the paid Infosimples fallback
 * — no native scraper yet (portal unverified / layout unknown). Currently CE.
 *
 * <p>It has no HTML fetch of its own: {@link #fetchHtml} throws a rescuable
 * {@link SefazFetchException} so {@link SefazIngestionService} routes the receipt
 * to Infosimples (a scanned full URL takes this path). A manually-typed bare chave
 * is routed to Infosimples even earlier, via {@link #requiresQrSignature()} — so
 * users in these states can add a receipt by scanning the QR OR by typing the
 * 44-digit chave printed on the coupon.
 *
 * <p>Registered ONLY when Infosimples is enabled ({@code economizaai.infosimples.enabled=true}):
 * with the fallback off, claiming these UFs would let a submit through only to fail
 * async, so instead the state stays unsupported and submit fails fast at the edge.
 * Every fetch here costs an Infosimples query (~R$0.24) — see DEV_NOTES.md.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "economizaai.infosimples", name = "enabled", havingValue = "true")
public class InfosimplesBackedAdapter implements SefazAdapter {

    private final Set<UnidadeFederativa> supportedStates;

    public InfosimplesBackedAdapter(
            @Value("${economizaai.ingestion.sefaz.infosimples-only-states:CE}") String states) {
        this.supportedStates = parseStates(states);
        log.info("InfosimplesBackedAdapter active for UFs: {} (fetch always via Infosimples fallback)",
                this.supportedStates);
    }

    private Set<UnidadeFederativa> parseStates(String csv) {
        var result = EnumSet.noneOf(UnidadeFederativa.class);
        if (csv == null) return result;
        for (var token : csv.split(",")) {
            var trimmed = token.trim().toUpperCase();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(UnidadeFederativa.valueOf(trimmed));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown UF '{}' in infosimples-only-states", trimmed);
            }
        }
        return result;
    }

    @Override
    public Set<UnidadeFederativa> supportedStates() {
        return supportedStates;
    }

    /** No native scraper: a bare chave is routed straight to Infosimples by the ingestion service. */
    @Override
    public boolean requiresQrSignature() {
        return true;
    }

    /** No native fetch — signal a rescuable failure so the Infosimples fallback handles it. */
    @Override
    public String fetchHtml(String qrPayload) {
        var uf = ChaveAcessoParser.extractUf(ChaveAcessoParser.extractChave(qrPayload));
        log.info("infosimples-only.fetch uf={} — deferring to Infosimples fallback", uf);
        throw new SefazFetchException(uf.name());
    }

    /** Never called — Infosimples returns a pre-parsed receipt, so there's no HTML to parse. */
    @Override
    public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
        throw new UnsupportedOperationException("InfosimplesBackedAdapter has no HTML parser");
    }
}
