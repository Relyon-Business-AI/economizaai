package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.dto.response.GenericComparisonResponse;
import com.relyon.economizaai.dto.response.GenericComparisonResponse.GenericProductRow;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Ver todos os [tipo]": dado o produto escaneado, lista todos os produtos do mesmo tipo
 * ({@code generic_name_norm}) com o melhor preço perto do usuário. Reutiliza
 * {@link PriceIndexService#bestMarketsForViewer} por produto, então herda a cascata
 * "perto de você" e o gate k-anônimo sem tocar nas internas do índice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericComparisonService {

    /** Teto de marcas/tamanhos consultados (admin-only, consulta por produto é barata mas N queries). */
    private static final int MAX_PRODUCTS = 60;

    private final ProductRepository productRepository;
    private final PriceIndexService priceIndexService;
    private final ViewerLocationService viewerLocationService;
    private final WatchedMarketService watchedMarketService;

    @Transactional(readOnly = true)
    public GenericComparisonResponse forProduct(UUID productId, User user) {
        var scanned = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var typeKey = scanned.getGenericNameNorm();
        var typeName = scanned.getGenericName();
        var location = viewerLocationService.resolve(user);
        var watched = watchedMarketService.watchedCnpjs(user);
        if (typeKey == null) {
            // Sem tipo genérico resolvido: nada pra generalizar, devolve só o escaneado.
            return new GenericComparisonResponse(typeName, productId,
                    List.of(toRow(scanned, productId, location, watched)));
        }
        var siblings = productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc(typeKey);
        if (siblings.size() > MAX_PRODUCTS) {
            log.info("generic_comparison.capped type={} total={} cap={}", typeKey, siblings.size(), MAX_PRODUCTS);
            siblings = siblings.subList(0, MAX_PRODUCTS);
        }
        return rank(typeName, typeKey, productId, siblings, location, watched);
    }

    /**
     * Comparação genérica por TIPO (nome livre), quando só temos a prévia do catálogo e não há
     * um produto exato pra ancorar. Normaliza o termo e agrupa por {@code generic_name_norm}.
     */
    @Transactional(readOnly = true)
    public GenericComparisonResponse forType(String rawType, User user) {
        var typeKey = DescriptionNormalizer.normalizeOrNull(rawType);
        if (typeKey == null) {
            return new GenericComparisonResponse(rawType, null, List.of());
        }
        var location = viewerLocationService.resolve(user);
        var watched = watchedMarketService.watchedCnpjs(user);
        var siblings = productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc(typeKey);
        if (siblings.size() > MAX_PRODUCTS) {
            log.info("generic_comparison.capped type={} total={} cap={}", typeKey, siblings.size(), MAX_PRODUCTS);
            siblings = siblings.subList(0, MAX_PRODUCTS);
        }
        // typeName de display: o genericName de um produto do tipo (casa a acentuação do catálogo), ou o termo.
        var typeName = siblings.stream().map(Product::getGenericName).filter(name -> name != null)
                .findFirst().orElse(rawType);
        return rank(typeName, typeKey, null, siblings, location, watched);
    }

    private GenericComparisonResponse rank(String typeName, String typeKey, UUID scannedId,
                                           List<Product> siblings, ViewerLocation location, Set<String> watched) {
        var rows = siblings.stream()
                .map(sibling -> toRow(sibling, scannedId, location, watched))
                .sorted(byCheapestFirst())
                .toList();
        log.info("generic_comparison.done type={} products={} priced={}",
                typeKey, rows.size(), rows.stream().filter(GenericProductRow::hasPrice).count());
        return new GenericComparisonResponse(typeName, scannedId, rows);
    }

    private GenericProductRow toRow(Product product, UUID scannedId, ViewerLocation location, Set<String> watched) {
        var cheapest = cheapestNearby(product.getId(), location, watched);
        var packLabel = packLabelOf(product);
        var label = labelOf(product, packLabel);
        var scanned = product.getId().equals(scannedId);
        if (cheapest == null) {
            return new GenericProductRow(product.getId(), label, product.getBrand(), packLabel,
                    scanned, false, null, null, null, null, 0, 0);
        }
        return new GenericProductRow(product.getId(), label, product.getBrand(), packLabel,
                scanned, true, cheapest.latestPrice(), cheapest.medianPrice(), cheapest.marketName(),
                cheapest.latestObservedAt(), cheapest.sampleCount(), cheapest.distinctHouseholds());
    }

    /** O mercado mais barato (por preço típico) perto do usuário para este produto, ou null. */
    private MarketPriceRow cheapestNearby(UUID productId, ViewerLocation location, Set<String> watched) {
        return priceIndexService.bestMarketsForViewer(productId, 10, location, watched).stream()
                .min(Comparator.comparing(MarketPriceRow::medianPrice,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Com preço primeiro, do mais barato ao mais caro; sem preço por último, em ordem alfabética. */
    private static Comparator<GenericProductRow> byCheapestFirst() {
        return Comparator
                .comparing(GenericProductRow::hasPrice, Comparator.reverseOrder())
                .thenComparing(row -> row.price() == null ? BigDecimal.ZERO : row.price())
                .thenComparing(GenericProductRow::label, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static String labelOf(Product product, String packLabel) {
        var base = product.getBrand() != null && !product.getBrand().isBlank()
                ? product.getBrand()
                : product.getGenericName();
        if (base == null) base = product.getNormalizedName();
        return packLabel == null ? base : base + " " + packLabel;
    }

    private static String packLabelOf(Product product) {
        if (product.getPackSize() == null) return null;
        var size = product.getPackSize().stripTrailingZeros().toPlainString();
        var unit = product.getPackUnit() == null ? "" : product.getPackUnit().toLowerCase();
        return size + unit;
    }
}
