package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolve o TIPO canônico de um produto ({@code generic_name_norm}, ex.: "creme de leite") a
 * partir de uma descrição crua — o nome do catálogo (Open Food Facts) ou a prévia de um scan.
 *
 * <p>Reutiliza o MESMO {@link ProductExtractor} da ingestão de nota (que transforma a descrição
 * de um item em {@code genericName}), em vez de uma heurística de prefixo: nomes de catálogo são
 * ruidosos ("Creme De Leite Esterilizado Bate Chantilly Nestlé Lata 280g", e até falso-positivos
 * como "Macarrão ... Creme De Leite Nissin"), então só o dicionário/classificador resolve o tipo
 * corretamente. Fallback para o nome cru normalizado quando o extrator não resolve nada.
 */
@Service
@RequiredArgsConstructor
public class ProductTypeResolver {

    private final ProductExtractor productExtractor;

    /** Chave do tipo ({@code generic_name_norm}) ou null quando não há descrição. */
    public String typeKey(String rawDescription) {
        return DescriptionNormalizer.normalizeOrNull(canonicalGenericName(rawDescription));
    }

    /** {@code genericName} canônico de display (ex.: "Creme de Leite"), ou o nome cru no fallback. */
    public String canonicalGenericName(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return null;
        var extracted = productExtractor.extract(rawDescription);
        return extracted.genericName() != null ? extracted.genericName() : rawDescription;
    }
}
