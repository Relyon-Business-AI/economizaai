package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.GenericComparisonResponse;
import com.relyon.economizaai.dto.response.GenericComparisonResponse.GenericOffer;
import com.relyon.economizaai.dto.response.PriceHistoryResponse;
import com.relyon.economizaai.dto.response.PriceHistoryResponse.PricePoint;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductMarketHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.extraction.ProductTypeResolver;
import com.relyon.economizaai.service.geo.CnpjNormalizer;
import com.relyon.economizaai.service.geo.DistanceCalculator;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * "Ver todos os [tipo]": dado o produto escaneado (ou um tipo livre), devolve uma LISTA PLANA
 * de ofertas de todos os produtos do mesmo tipo ({@code generic_name_norm}, ex.: "creme de
 * leite"), uma linha por (produto, mercado) usando a observação mais recente daquele par, do
 * mais barato ao mais caro. É uma "faixa de preço de referência" — marcas/tamanhos diferentes.
 *
 * <p>Escopo: ao contrário do best-markets de UM produto (que para no raio "perto de você"), esta
 * visão ALARGA explicitamente a cascata — raio (10 km) → UF → nacional — até achar ofertas, já
 * que o ponto é mostrar a faixa do tipo. K-anônimo por tier, por (produto, mercado): mercearia
 * n=1, farmácia/saúde K=3 (fail-closed, igual ao resto do índice).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericComparisonService {

    /** Teto de marcas/tamanhos agregados (admin-only). */
    private static final int MAX_PRODUCTS = 60;
    /** Teto de ofertas devolvidas (lista plana, mais baratas primeiro). */
    private static final int MAX_OFFERS = 50;

    private final ProductRepository productRepository;
    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final MarketLocationService marketLocationService;
    private final MarketNameService marketNameService;
    private final ViewerLocationService viewerLocationService;
    private final PrivacyTierResolver privacyTierResolver;
    private final ProductTypeResolver productTypeResolver;
    private final HouseholdProductAliasService householdProductAliasService;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public GenericComparisonResponse forProduct(UUID productId, User user) {
        var scanned = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var typeKey = scanned.getGenericNameNorm();
        var typeName = scanned.getGenericName();
        if (typeKey == null) {
            // Sem tipo genérico resolvido: nada pra generalizar, usa só o escaneado.
            return build(typeName, typeKey, productId, List.of(scanned), user);
        }
        return build(typeName, typeKey, productId, siblings(typeKey), user);
    }

    /**
     * Comparação genérica por TIPO (nome livre), quando só temos a prévia do catálogo e não há
     * um produto exato pra ancorar. Normaliza o termo e agrupa por {@code generic_name_norm}.
     */
    @Transactional(readOnly = true)
    public GenericComparisonResponse forType(String rawType, User user) {
        // Nomes de catálogo (OFF) são crus ("CREME DE LEITE UHT NESTLE") — resolve o tipo canônico
        // pelo MESMO extrator da ingestão de nota, senão a busca por generic_name_norm casa 0.
        var typeKey = productTypeResolver.typeKey(rawType);
        if (typeKey == null) {
            return new GenericComparisonResponse(rawType, null, null, List.of());
        }
        var siblings = siblings(typeKey);
        // typeName de display: o genericName de um produto do tipo (casa a acentuação), senão o
        // tipo canônico resolvido, senão o termo cru.
        var typeName = siblings.stream().map(Product::getGenericName).filter(name -> name != null)
                .findFirst().orElseGet(() -> productTypeResolver.canonicalGenericName(rawType));
        return build(typeName, typeKey, null, siblings, user);
    }

    /**
     * Série de preço no tempo de UM produto num UM mercado (par produto, CNPJ), para o gráfico
     * stock/dólar da linha expandida da comparação genérica. Reusa {@code PriceHistoryResponse}
     * pra o FE aproveitar o mesmo gráfico. Mesmo gate k-anônimo por tier do par usado nas ofertas
     * (mercearia n=1, farmácia/saúde K=3, fail-closed): bloqueado devolve série vazia, nunca parcial.
     */
    @Transactional(readOnly = true)
    public PriceHistoryResponse priceHistoryForMarket(UUID productId, String marketCnpj, User user) {
        var product = productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
        var cnpj = CnpjNormalizer.normalize(marketCnpj);
        var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getBestMarketsLookbackDays());
        var observations = observationRepository.findRecentByProductAndMarket(productId, cnpj, since);
        var friendlyDescription = householdProductAliasService.findFor(user.getHousehold(), product);
        if (observations.isEmpty() || isBlockedByKanon(product, cnpj, observations, since)) {
            log.info("price_history_market.done product={} cnpj={} points=0 blocked={}",
                    productId, cnpj, !observations.isEmpty());
            return new PriceHistoryResponse(productId, product.getNormalizedName(), friendlyDescription, List.of());
        }
        var rawName = observations.get(0).getMarketName();
        var marketFriendlyName = marketNameService.friendlyName(user.getHousehold().getId(), cnpj, rawName);
        // ASC no tempo: o gráfico de linha desenha do mais antigo ao mais recente (igual às insights).
        var points = observations.stream()
                .sorted(Comparator.comparing(PriceObservation::getObservedAt))
                .map(observation -> new PricePoint(observation.getObservedAt(), cnpj, rawName,
                        marketFriendlyName, observation.getUnitPrice(), observation.getQuantity()))
                .toList();
        log.info("price_history_market.done product={} cnpj={} points={}", productId, cnpj, points.size());
        return new PriceHistoryResponse(productId, product.getNormalizedName(), friendlyDescription, points);
    }

    /** K-anon do par (produto, mercado): conta todas as observações do par + households distintos. */
    private boolean isBlockedByKanon(Product product, String cnpj,
                                     List<PriceObservation> observations, LocalDateTime since) {
        var households = auditRepository.countDistinctHouseholdsForProductMarket(product.getId(), cnpj, since);
        var sensitiveProduct = privacyTierResolver.isSensitiveProduct(product.getId());
        var sensitiveMarket = PrivacyTierResolver.isSensitiveMarket(
                marketLocationService.findByCnpjs(List.of(cnpj)).get(cnpj));
        var thresholds = privacyTierResolver.thresholdsFor(sensitiveProduct || sensitiveMarket);
        return thresholds.blocks(observations.size(), households);
    }

    private List<Product> siblings(String typeKey) {
        var found = productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc(typeKey);
        if (found.size() > MAX_PRODUCTS) {
            log.info("generic_comparison.capped type={} total={} cap={}", typeKey, found.size(), MAX_PRODUCTS);
            return found.subList(0, MAX_PRODUCTS);
        }
        return found;
    }

    private GenericComparisonResponse build(String typeName, String typeKey, UUID scannedId,
                                            List<Product> products, User user) {
        if (!properties.getCollaborative().isEnabled() || products.isEmpty()) {
            return new GenericComparisonResponse(typeName, scannedId, null, List.of());
        }
        var productIds = products.stream().map(Product::getId).toList();
        var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getBestMarketsLookbackDays());
        var observations = observationRepository.findRecentByProductIds(productIds, since);
        if (observations.isEmpty()) {
            log.info("generic_comparison.done type={} products={} offers=0 scope=NONE", typeKey, products.size());
            return new GenericComparisonResponse(typeName, scannedId, null, List.of());
        }

        var context = new OfferContext(products, observations, scannedId, user);
        // Cascata explícita de "faixa de referência": raio → UF → nacional, a primeira que
        // tiver ofertas (diferente do best-markets de um produto, que para no raio).
        var cascade = cascade(context);
        for (var scope : cascade) {
            var offers = offersInScope(context, scope.filter());
            if (!offers.isEmpty()) {
                log.info("generic_comparison.done type={} products={} offers={} scope={}",
                        typeKey, products.size(), offers.size(), scope.name());
                return new GenericComparisonResponse(typeName, scannedId, scope.name(), offers);
            }
        }
        log.info("generic_comparison.done type={} products={} offers=0 scope=NATIONAL", typeKey, products.size());
        return new GenericComparisonResponse(typeName, scannedId, "NATIONAL", List.of());
    }

    /** NEARBY (raio, só com coordenadas) → STATE (UF majoritária) → NATIONAL, nessa ordem. */
    private List<Scope> cascade(OfferContext context) {
        var cascade = new ArrayList<Scope>();
        var location = context.location();
        if (location.hasCoordinates()) {
            var radiusKm = properties.getCollaborative().getBestMarketsNearbyRadiusKm();
            cascade.add(new Scope("NEARBY", observation -> {
                var distance = context.distanceKm(observation);
                return distance != null && distance <= radiusKm;
            }));
        }
        if (location.uf() != null) {
            cascade.add(new Scope("STATE", observation -> location.uf().equals(observation.getState())));
        }
        cascade.add(new Scope("NATIONAL", observation -> true));
        return cascade;
    }

    private List<GenericOffer> offersInScope(OfferContext context, Predicate<PriceObservation> inScope) {
        return context.observations().stream()
                .filter(inScope)
                .filter(context::passesKanon)
                // Uma linha por (produto, mercado): a observação MAIS RECENTE daquele par.
                .collect(Collectors.groupingBy(observation ->
                        new ProductMarket(observation.getProduct().getId(), observation.getMarketCnpj())))
                .values().stream()
                .map(pairRows -> pairRows.stream()
                        .max(Comparator.comparing(PriceObservation::getObservedAt)).orElseThrow())
                .map(context::toOffer)
                .sorted(Comparator.comparing(GenericOffer::unitPrice,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_OFFERS)
                .toList();
    }

    private record ProductMarket(UUID productId, String marketCnpj) {}

    private record Scope(String name, Predicate<PriceObservation> filter) {}

    /** Per-request state: everything the per-observation steps need, resolved once. */
    private final class OfferContext {
        private final Map<UUID, Product> productsById;
        private final List<PriceObservation> observations;
        private final UUID scannedId;
        private final ViewerLocation location;
        private final Map<String, MarketLocation> marketLocations;
        private final Map<ProductMarket, Long> householdsByPair;
        private final Map<ProductMarket, Long> observationsByPair;
        private final Map<UUID, Boolean> sensitiveByProduct;
        private final Map<String, String> marketNameOverrides;
        private final Map<String, String> chainNames;

        OfferContext(List<Product> products, List<PriceObservation> observations, UUID scannedId, User user) {
            this.productsById = products.stream().collect(Collectors.toMap(Product::getId, product -> product));
            this.observations = observations;
            this.scannedId = scannedId;
            this.location = viewerLocationService.resolve(user);
            var cnpjs = observations.stream().map(PriceObservation::getMarketCnpj).distinct().toList();
            this.marketLocations = marketLocationService.findByCnpjs(new ArrayList<>(cnpjs));
            this.marketNameOverrides = marketNameService.resolveNames(user.getHousehold().getId(), cnpjs);
            this.chainNames = marketNameService.resolveChainNames(cnpjs);
            var productIds = new ArrayList<>(productsById.keySet());
            var since = BrazilClock.nowDateTime().minusDays(properties.getCollaborative().getBestMarketsLookbackDays());
            this.householdsByPair = auditRepository.countDistinctHouseholdsForProductsByMarket(productIds, since).stream()
                    .collect(Collectors.toMap(
                            count -> new ProductMarket(count.getProductId(), count.getCnpj()),
                            ProductMarketHouseholdCount::getHouseholds));
            this.observationsByPair = observations.stream()
                    .collect(Collectors.groupingBy(
                            observation -> new ProductMarket(observation.getProduct().getId(), observation.getMarketCnpj()),
                            Collectors.counting()));
            this.sensitiveByProduct = productIds.stream()
                    .collect(Collectors.toMap(id -> id, privacyTierResolver::isSensitiveProduct));
        }

        List<PriceObservation> observations() { return observations; }

        ViewerLocation location() { return location; }

        /** K-anon por tier do PAR (produto, mercado): mercearia n=1; farmácia/saúde K=3 (fail-closed). */
        boolean passesKanon(PriceObservation observation) {
            var pair = new ProductMarket(observation.getProduct().getId(), observation.getMarketCnpj());
            var households = householdsByPair.getOrDefault(pair, 0L);
            var sensitiveProduct = sensitiveByProduct.getOrDefault(observation.getProduct().getId(), true);
            var sensitiveMarket = PrivacyTierResolver.isSensitiveMarket(marketLocations.get(observation.getMarketCnpj()));
            var thresholds = privacyTierResolver.thresholdsFor(sensitiveProduct || sensitiveMarket);
            // Uma oferta é a observação mais recente do par, mas o gate conta TODAS as observações
            // do par no período — igual ao resto do índice.
            var sampleCount = observationsByPair.getOrDefault(pair, 0L).intValue();
            return !thresholds.blocks(sampleCount, households);
        }

        Double distanceKm(PriceObservation observation) {
            if (!location.hasCoordinates()) return null;
            var market = marketLocations.get(observation.getMarketCnpj());
            if (market == null || !market.hasCoordinates()) return null;
            return DistanceCalculator.kmBetween(location.latitude(), location.longitude(),
                    market.getLatitude(), market.getLongitude());
        }

        GenericOffer toOffer(PriceObservation observation) {
            var product = productsById.get(observation.getProduct().getId());
            var packLabel = packLabelOf(product);
            var scanned = product.getId().equals(scannedId);
            var friendlyName = marketNameService.friendlyName(
                    marketNameOverrides, chainNames, observation.getMarketCnpj(), observation.getMarketName());
            return new GenericOffer(product.getId(), labelOf(product, packLabel), product.getBrand(), packLabel,
                    observation.getUnitPrice(), observation.getMarketCnpj(), observation.getMarketName(), friendlyName,
                    observation.getCity(), observation.getState(), distanceKm(observation), observation.getObservedAt(),
                    observation.getChannel() == null ? null : observation.getChannel().name(), scanned);
        }
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
