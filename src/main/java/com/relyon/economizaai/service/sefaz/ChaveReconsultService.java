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
 *   captcha de imagem; cada fetch custa ~1 solve);</li>
 *   <li><b>CE</b> — {@link CearaNfcePortalAdapter} (a API qrcodev3 já consulta
 *   por chave pura, sem captcha — grátis);</li>
 *   <li><b>AM</b> — {@link AmChaveReconsultClient} (GET público, sem captcha — grátis;
 *   DANFE responsivo padrão lido pelo parser genérico, o "novo RS").</li>
 * </ul>
 * Demais UFs seguem não-reconsultáveis (rejeição localizada no submit do import).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChaveReconsultService {

    /** Parser genérico do DANFE responsivo padrão — serve BA e AM (fetch acontece antes, no client). */
    private static final ReconsultDanfeAdapter DANFE_ADAPTER = new ReconsultDanfeAdapter();

    private final RsChaveReconsultService rsService;
    private final BahiaChaveConsultClient bahiaClient;
    private final CearaNfcePortalAdapter cearaAdapter;
    private final AmChaveReconsultClient amClient;

    /** True when a bare chave from this UF/model can be reconsulted for its items. */
    public static boolean isReconsultable(String chave) {
        if (RsChaveReconsultService.isReconsultable(chave)) return true;
        var uf = ChaveAcessoParser.extractUf(chave);
        return (uf == UnidadeFederativa.BA || uf == UnidadeFederativa.CE || uf == UnidadeFederativa.AM)
                && "65".equals(ChaveAcessoParser.extractModel(chave));
    }

    /**
     * True when this chave's reconsult sits behind a captcha (BA's image "anti-robô") — every
     * fetch buys at least one solve, so callers must meter it like any other paid captcha.
     */
    public static boolean solvesCaptcha(String chave) {
        return ChaveAcessoParser.extractUf(chave) == UnidadeFederativa.BA;
    }

    public FetchedDocument reconsult(String chave) {
        var uf = ChaveAcessoParser.extractUf(chave);
        if (uf == UnidadeFederativa.RS) {
            return rsService.reconsult(chave);
        }
        if (uf == UnidadeFederativa.BA) {
            var rawHtml = bahiaClient.fetchByChave(chave);
            var sanitized = CpfMasker.strip(rawHtml);
            return new FetchedDocument(DANFE_ADAPTER, sanitized, chave, uf,
                    "http://nfe.sefaz.ba.gov.br/servicos/nfce (reconsulta chave)");
        }
        if (uf == UnidadeFederativa.CE) {
            // a API do CE já é por chave — o adapter de QR serve o import sem mudança
            var rawHtml = CpfMasker.strip(cearaAdapter.fetchHtml(chave));
            return new FetchedDocument(cearaAdapter, rawHtml, chave, uf,
                    "http://nfce.sefaz.ce.gov.br/nfce/api/notasFiscal/qrcodev3 (reconsulta chave)");
        }
        if (uf == UnidadeFederativa.AM) {
            var rawHtml = CpfMasker.strip(amClient.fetchByChave(chave));
            return new FetchedDocument(DANFE_ADAPTER, rawHtml, chave, uf,
                    "https://sistemas.sefaz.am.gov.br/nfceweb/consultarNFCe.jsp (reconsulta chave)");
        }
        throw new UnsupportedStateException(uf.name());
    }

    /**
     * Não é bean Spring (um {@code supportedStates()} colidiria com o adapter de QR da BA) —
     * roteador stateless só pro parse do reconsult (DANFE responsivo padrão, BA e AM). O fetch
     * acontece antes, em {@link BahiaChaveConsultClient} / {@link AmChaveReconsultClient}.
     */
    static final class ReconsultDanfeAdapter implements SefazAdapter {
        @Override
        public Set<UnidadeFederativa> supportedStates() {
            return Set.of();
        }

        @Override
        public String fetchHtml(String qrPayload) {
            throw new ReceiptParseException("reconsult-fetches-up-front");
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
