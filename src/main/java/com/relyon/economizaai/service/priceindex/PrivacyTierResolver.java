package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides which k-anonymity thresholds an aggregate read must honor.
 *
 * <p>Two tiers. SENSITIVE keeps the full K (3 households): products in a sensitive
 * category group (health) and anything observed at a PHARMACY — a disclosed price there
 * says what a person is treating. GROCERY uses the relaxed thresholds (K=1): "cenoura at
 * Zaffari on 30/10 for R$ 4,99" is the same public fact the state "menor preço" portals
 * publish straight from NFC-e, and with {@code observedAt} truncated to the day nothing
 * lets a reader stitch observations back into one receipt.
 *
 * <p>Only AGGREGATE reads (best-markets, reference prices) consult this; every list-shaped
 * read (admin, exports, merchant panel, B2B) keeps the full K unconditionally.
 */
@Service
@RequiredArgsConstructor
public class PrivacyTierResolver {

    private final ProductRepository productRepository;
    private final CategoryResolver categoryResolver;
    private final CollaborativeProperties properties;

    public record Thresholds(int minHouseholds, int minObservations) {
        public boolean blocks(int observations, long households) {
            return observations < minObservations || households < minHouseholds;
        }
    }

    /** Unknown products are treated as sensitive — never relax on missing data. */
    public boolean isSensitiveProduct(UUID productId) {
        return productRepository.findById(productId)
                .map(this::isSensitiveProduct)
                .orElse(true);
    }

    private boolean isSensitiveProduct(Product product) {
        var groupSlug = categoryResolver.slugForId(categoryResolver.groupIdOf(product.getCategoryId()));
        if (groupSlug != null) {
            return properties.getCollaborative().getSensitiveCategoryGroups().contains(groupSlug);
        }
        return product.getCategory() == ProductCategory.HEALTH;
    }

    public static boolean isSensitiveMarket(MarketLocation location) {
        return location != null && location.getSegment() == MerchantSegment.PHARMACY;
    }

    public static boolean anySensitiveMarket(Collection<MarketLocation> locations) {
        return locations.stream().filter(Objects::nonNull).anyMatch(PrivacyTierResolver::isSensitiveMarket);
    }

    public Thresholds thresholdsFor(boolean sensitive) {
        var collaborative = properties.getCollaborative();
        return sensitive
                ? new Thresholds(collaborative.getMinHouseholdsForPublic(), collaborative.getMinObservationsPerProductMarket())
                : new Thresholds(collaborative.getMinHouseholdsForGrocery(), collaborative.getMinObservationsForGrocery());
    }
}
