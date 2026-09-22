package com.relyon.economizaai.service.canonicalization;

import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.repository.MarketLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;

/**
 * Decides whether a merchant is a pharmacy, for the category fallback (items a
 * drugstore sells that the dictionary can't place default to PHARMACY).
 *
 * <p>Two signals, best-first: (1) the CNAE-verified {@code segment} stored on
 * {@link com.relyon.economizaai.model.MarketLocation} (resolved asynchronously
 * from the CNPJ); (2) when that isn't available yet, the merchant name pattern.
 * The name layer means the fallback works immediately at confirm time even
 * before the external CNAE lookup has run — and if the lookup never succeeds,
 * we simply keep using the name guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantClassifier {

    private final MarketLocationRepository marketLocationRepository;

    // Substrings (normalized: uppercase, no accents) that reliably mark a
    // pharmacy/drugstore by name. Conservative — bare ambiguous tokens excluded.
    private static final List<String> PHARMACY_MARKERS = List.of(
            "FARMACIA", "DROGARIA", "DROGASIL", "DROGA RAIA", "RAIA DROGASIL",
            "DROGAO", "DROGAL", "DROGAFUJI", "PANVEL", "PAGUE MENOS",
            "ULTRAFARMA", "BIFARMA", "NISSEI", "AGAFARMA", "PERMANENTE FARMA",
            "VENANCIO", "PACHECO", "DIMED", "PANARELLO", "PROFARMA",
            "DISTRIBUIDORA DE MEDICAMENTOS", "PRODUTOS FARMACEUTICOS");

    /**
     * True if the merchant is a pharmacy. Prefers the CNAE-verified segment;
     * a verified non-pharmacy segment (e.g. SUPERMARKET) wins over the name
     * guess. Falls back to the name pattern only while the segment is UNKNOWN.
     */
    public boolean isPharmacy(String cnpj, String marketName) {
        if (cnpj != null && !cnpj.isBlank()) {
            var segment = marketLocationRepository.findByCnpj(cnpj)
                    .map(market -> market.getSegment())
                    .orElse(MerchantSegment.UNKNOWN);
            if (segment == MerchantSegment.PHARMACY) return true;
            if (segment != MerchantSegment.UNKNOWN) return false; // verified, and not a pharmacy
        }
        return matchesPharmacyName(marketName);
    }

    private boolean matchesPharmacyName(String marketName) {
        if (marketName == null || marketName.isBlank()) return false;
        var normalized = normalize(marketName);
        var match = PHARMACY_MARKERS.stream().anyMatch(normalized::contains);
        if (match) {
            log.debug("merchant.classified_by_name segment=PHARMACY name='{}'", marketName);
        }
        return match;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase();
    }
}
