package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.exception.UnsupportedStateException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.service.sefaz.SefazIngestionService.FetchedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Reconsults an RS nota from a <b>bare 44-digit chave</b> and returns a
 * {@link FetchedDocument} the standard ingestion pipeline can persist — the
 * engine behind the reconsult bulk import ({@code POST /receipts/import}).
 *
 * <p>Only RS is served today, via the two public portals ({@link RsChaveReconsultClient}):
 * NFC-e (65) through SAT-WEB, NF-e (55) through the SVRS public consult. The
 * document it returns carries a lightweight non-registered adapter
 * ({@link ReconsultAdapter}) so {@link SefazIngestionService#parse} routes to the
 * right parser by model — which also means a parse failure keeps the raw HTML for
 * review, exactly like a normal scan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RsChaveReconsultService {

    private static final ReconsultAdapter ADAPTER = new ReconsultAdapter();

    private final RsChaveReconsultClient client;

    /** True when a bare chave from this UF/model can be reconsulted for its items. */
    public static boolean isReconsultable(String chave) {
        if (ChaveAcessoParser.extractUf(chave) != UnidadeFederativa.RS) {
            return false;
        }
        var model = ChaveAcessoParser.extractModel(chave);
        return "65".equals(model) || "55".equals(model);
    }

    public FetchedDocument reconsult(String chave) {
        var uf = ChaveAcessoParser.extractUf(chave);
        if (uf != UnidadeFederativa.RS) {
            throw new UnsupportedStateException(uf.name());
        }
        var model = ChaveAcessoParser.extractModel(chave);
        var rawHtml = "65".equals(model) ? client.fetchNfceSatWeb(chave)
                : "55".equals(model) ? client.fetchNfe55(chave)
                : null;
        if (rawHtml == null) {
            throw new ReceiptParseException("reconsult-unsupported-model");
        }
        var sanitized = CpfMasker.strip(rawHtml);
        var sourceUrl = "https://www.sefaz.rs.gov.br/NFCE (reconsulta chave " + model + ")";
        log.info("reconsult.fetched model={} chave={} bytes={}", model, abbrev(chave), sanitized.length());
        return new FetchedDocument(ADAPTER, sanitized, chave, uf, sourceUrl);
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    /**
     * Not a Spring bean (empty {@link #supportedStates()} would collide with the
     * SVRS adapter on RS) — a stateless router that picks the reconsult parser by
     * the chave's model. Fetching is done up front by {@link RsChaveReconsultClient},
     * so {@link #fetchHtml} is never called on this instance.
     */
    static final class ReconsultAdapter implements SefazAdapter {
        @Override
        public Set<UnidadeFederativa> supportedStates() {
            return Set.of();
        }

        @Override
        public String fetchHtml(String qrPayload) {
            throw new UnsupportedOperationException("reconsult adapter is parse-only");
        }

        @Override
        public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
            var model = ChaveAcessoParser.extractModel(chaveAcesso);
            if ("65".equals(model)) {
                return SatWebNfceParser.parse(html, chaveAcesso, sourceUrl);
            }
            if ("55".equals(model)) {
                return SvrsNfeProdutosParser.parse(html, chaveAcesso, sourceUrl);
            }
            throw new ReceiptParseException("reconsult-unsupported-model");
        }
    }
}
