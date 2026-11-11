package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.NfeXmlSignatureVerifier.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides whether a receipt born from a USER-UPLOADED XML may feed the collaborative price
 * index. The file is client-authored: without proof of origin it is personal history only.
 *
 * <ul>
 *   <li>In-store XML (channel IN_STORE) never feeds the index — the physical index is fed by
 *       documents our own server fetched from SEFAZ.</li>
 *   <li>Online XML is eligible only when its authenticity is established, by either:
 *     <ol>
 *       <li>the issuer's digital signature, valid and anchored in ICP-Brasil, covering exactly
 *           the content we parsed; or</li>
 *       <li>a server-side reconsult of the chave (paid, under the per-user cap, the daily
 *           budget and the ledger) whose issuer, total and item prices match the file.</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>The reconsult is an outbound HTTP call: callers must invoke this OUTSIDE any DB transaction
 * (the ingestion does, between the parse and the persist block). Never throws — any failure to
 * establish authenticity is a "no".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XmlIndexEligibilityService {

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final String NFE_MODEL = "55";

    private final NfeXmlSignatureVerifier signatureVerifier;
    private final SefazIngestionService sefazIngestionService;
    private final UserRepository userRepository;
    private final CollaborativeProperties properties;

    /** Kill switch for the paid confirmation (signature-only mode). Field-injected: keeps the Lombok constructor. */
    @Value("${economizaai.xml-import.paid-reconsult-enabled:true}")
    private boolean paidReconsultEnabled = true;

    public boolean isIndexEligible(String uploadedXml, ParsedReceipt parsed, UUID userId) {
        var chave = LogMasker.chave(parsed.chaveAcesso());
        if (parsed.channel() != ReceiptChannel.ONLINE) {
            log.info("xml.index_eligibility.denied chave={} reason=in_store_xml", chave);
            return false;
        }
        if (!couldContribute(parsed, userId)) {
            log.info("xml.index_eligibility.denied chave={} reason=nothing_to_contribute", chave);
            return false;
        }
        var verdict = signatureVerifier.verify(uploadedXml, parsed.chaveAcesso());
        if (verdict.status() == Status.TRUSTED) {
            var signedContentMatches = sameContent(parsed, verdict.signedReceipt());
            log.info("xml.index_eligibility.{} chave={} reason={}", signedContentMatches ? "granted" : "denied",
                    chave, signedContentMatches ? "issuer_signature" : "parsed_content_not_covered_by_signature");
            return signedContentMatches;
        }
        var confirmed = confirmedByReconsult(parsed, userId);
        log.info("xml.index_eligibility.{} chave={} reason={} signature={}:{}", confirmed ? "granted" : "denied",
                chave, confirmed ? "reconsult_match" : "authenticity_not_established",
                verdict.status(), verdict.reason());
        return confirmed;
    }

    /**
     * No point spending a paid reconsult on a nota that cannot contribute anyway: index off,
     * owner opted out, or no line with a barcode (the online index is keyed by EAN).
     */
    private boolean couldContribute(ParsedReceipt parsed, UUID userId) {
        if (!properties.getCollaborative().isEnabled()) return false;
        var optedIn = userRepository.findById(userId).map(User::isContributionOptIn).orElse(false);
        return optedIn && parsed.items().stream().anyMatch(item -> item.ean() != null && !item.ean().isBlank());
    }

    /**
     * The signature proves the SIGNED infNFe; this proves that what we parsed (and will persist)
     * is exactly that content — an element smuggled in outside the signed subtree would differ.
     */
    private static boolean sameContent(ParsedReceipt parsed, ParsedReceipt signed) {
        if (signed == null) return false;
        return Objects.equals(parsed.cnpjEmitente(), signed.cnpjEmitente())
                && Objects.equals(parsed.issuedAt(), signed.issuedAt())
                && parsed.channel() == signed.channel()
                && sameAmount(parsed.totalAmount(), signed.totalAmount(), BigDecimal.ZERO)
                && sameItems(parsed.items(), signed.items());
    }

    private static boolean sameItems(List<ParsedReceiptItem> parsedItems, List<ParsedReceiptItem> signedItems) {
        if (parsedItems.size() != signedItems.size()) return false;
        for (var index = 0; index < parsedItems.size(); index++) {
            var parsedItem = parsedItems.get(index);
            var signedItem = signedItems.get(index);
            var same = Objects.equals(parsedItem.rawDescription(), signedItem.rawDescription())
                    && Objects.equals(parsedItem.ean(), signedItem.ean())
                    && sameAmount(parsedItem.quantity(), signedItem.quantity(), BigDecimal.ZERO)
                    && sameAmount(parsedItem.unitPrice(), signedItem.unitPrice(), BigDecimal.ZERO)
                    && sameAmount(parsedItem.totalPrice(), signedItem.totalPrice(), BigDecimal.ZERO);
            if (!same) return false;
        }
        return true;
    }

    /**
     * Fallback when the signature can't vouch for the file: ask SEFAZ (through the paid by-chave
     * provider) for the same chave and require the issuer, the total and every line total to
     * match. The provider result carries no channel, so the file's "online" claim is only
     * accepted for model 55 (NF-e) — an NFC-e is a counter sale unless proven otherwise.
     */
    private boolean confirmedByReconsult(ParsedReceipt parsed, UUID userId) {
        if (!paidReconsultEnabled) return false;
        var chave = parsed.chaveAcesso();
        if (!NFE_MODEL.equals(ChaveAcessoParser.extractModel(chave))) {
            log.debug("xml.index_eligibility.reconsult_skipped chave={} reason=not_model_55", LogMasker.chave(chave));
            return false;
        }
        try {
            var reconsulted = sefazIngestionService
                    .fetchViaInfosimplesFallback(chave, ChaveAcessoParser.extractUf(chave), userId)
                    .preParsed();
            return reconsulted != null && matchesReconsult(parsed, reconsulted);
        } catch (RuntimeException ex) {
            log.warn("xml.index_eligibility.reconsult_failed chave={} reason={}",
                    LogMasker.chave(chave), ex.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean matchesReconsult(ParsedReceipt parsed, ParsedReceipt reconsulted) {
        if (parsed.cnpjEmitente() == null || !parsed.cnpjEmitente().equals(digitsOf(reconsulted.cnpjEmitente()))) {
            return false;
        }
        if (parsed.totalAmount() == null || !sameAmount(parsed.totalAmount(), reconsulted.totalAmount(), ONE_CENT)) {
            return false;
        }
        var fileLineTotals = sortedLineTotals(parsed.items());
        var reconsultedLineTotals = sortedLineTotals(reconsulted.items());
        if (fileLineTotals.isEmpty() || fileLineTotals.size() != reconsultedLineTotals.size()) return false;
        for (var index = 0; index < fileLineTotals.size(); index++) {
            if (!sameAmount(fileLineTotals.get(index), reconsultedLineTotals.get(index), ONE_CENT)) return false;
        }
        return true;
    }

    /** Line order may differ between the file and the provider; the multiset of line totals may not. */
    private static List<BigDecimal> sortedLineTotals(List<ParsedReceiptItem> items) {
        return items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private static boolean sameAmount(BigDecimal first, BigDecimal second, BigDecimal tolerance) {
        if (first == null || second == null) return first == second;
        return first.subtract(second).abs().compareTo(tolerance) <= 0;
    }

    private static String digitsOf(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }
}
