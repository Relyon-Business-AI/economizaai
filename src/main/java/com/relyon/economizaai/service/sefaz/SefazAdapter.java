package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.UnidadeFederativa;

import java.util.Optional;
import java.util.Set;

public interface SefazAdapter {

    /**
     * UFs this adapter can fetch + parse. Returning a set (not a single UF)
     * lets one adapter cover several states that share infrastructure — most
     * obvious case: the SVRS hub serves NFC-e for many states beyond RS, so
     * a single SVRS adapter can claim all of them.
     */
    Set<UnidadeFederativa> supportedStates();

    /**
     * True when this portal can only serve the DANFE from a fully-signed QR URL,
     * so a manually-typed bare chave (no signature) can't be scraped and must be
     * routed to a by-chave fallback (Infosimples). Defaults to false — MS/SC
     * consult by chave natively (chNFe / ?p=chave), so a bare chave works there.
     */
    default boolean requiresQrSignature() {
        return false;
    }

    /**
     * Some portals wrap the original QR payload in a security URL before the
     * 44-digit chave is visible. Adapters can do a cheap portal-specific fetch
     * to recover it so submit can still persist a PROCESSING receipt with chave
     * and UF populated.
     */
    default Optional<String> preflightChave(String qrPayload) {
        return Optional.empty();
    }

    String fetchHtml(String qrPayload);

    ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl);
}
