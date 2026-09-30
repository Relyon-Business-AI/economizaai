package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazDeterministicFetchException;
import com.relyon.economizaai.exception.SefazFetchException;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Maps a portal 4xx to the right failure type for the fallback chain:
 * 403/429 (IP-block/throttle) are plausibly rescuable by the paid Infosimples
 * fallback; any other 4xx (bad/unknown chave) is deterministic — the fallback
 * would fail the exact same way, so it must not be paid for.
 */
final class PortalClientErrorClassifier {

    private PortalClientErrorClassifier() {
    }

    static SefazFetchException classify(HttpClientErrorException clientError, String ufName) {
        var status = clientError.getStatusCode().value();
        return status == 403 || status == 429
                ? new SefazFetchException(ufName)
                : new SefazDeterministicFetchException(ufName);
    }
}
