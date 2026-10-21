package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.UnsupportedStateException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.sefaz.SefazIngestionService.FetchedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Import por CHAVE PURA (44 dígitos digitados/extraídos, sem assinatura de QR),
 * roteado por UF:
 * <ul>
 *   <li><b>RS</b> — {@link RsChaveReconsultService} (portais públicos, grátis);</li>
 *   <li><b>BA</b> — {@link BahiaChaveConsultClient} (consulta pública gated por
 *   captcha de imagem; cada fetch custa ~1 solve).</li>
 * </ul>
 * Demais UFs seguem não-reconsultáveis (rejeição localizada no submit do import).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChaveReconsultService {

    private static final BahiaAdapter BA_ADAPTER = new BahiaAdapter();

    private final RsChaveReconsultService rsService;
    private final BahiaChaveConsultClient bahiaClient;

    /** True when a bare chave from this UF/model can be reconsulted for its items. */
    public static boolean isReconsultable(String chave) {
        if (RsChaveReconsultService.isReconsultable(chave)) return true;
        return ChaveAcessoParser.extractUf(chave) == UnidadeFederativa.BA
                && "65".equals(ChaveAcessoParser.extractModel(chave));
    }

    public FetchedDocument reconsult(String chave) {
        var uf = ChaveAcessoParser.extractUf(chave);
        if (uf == UnidadeFederativa.RS) {
            return rsService.reconsult(chave);
        }
        if (uf == UnidadeFederativa.BA) {
            var rawHtml = bahiaClient.fetchByChave(chave);
            var sanitized = CpfMasker.strip(rawHtml);
            return new FetchedDocument(BA_ADAPTER, sanitized, chave, uf,
                    "http://nfe.sefaz.ba.gov.br/servicos/nfce (reconsulta chave)");
        }
        throw new UnsupportedStateException(uf.name());
    }

    /**
     * Não é bean Spring (um {@code supportedStates()} com BA colidiria com o
     * adapter de QR da BA) — roteador stateless só pro parse do reconsult.
     * O fetch acontece antes, em {@link BahiaChaveConsultClient}.
     */
    static final class BahiaAdapter implements SefazAdapter {
        @Override
        public Set<UnidadeFederativa> supportedStates() {
            return Set.of();
        }

        @Override
        public String fetchHtml(String qrPayload) {
            throw new ReceiptParseException("ba-reconsult-fetches-up-front");
        }

        @Override
        public ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl) {
            return ResponsiveDanfeParser.parse(html, chaveAcesso, sourceUrl);
        }

        @Override
        public Optional<String> preflightChave(String qrPayload) {
            return Optional.empty();
        }
    }
}
