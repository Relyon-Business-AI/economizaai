package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductMarketHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.extraction.ProductTypeResolver;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenericComparisonServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private PrivacyTierResolver privacyTierResolver;
    @Mock private ProductTypeResolver productTypeResolver;

    private final CollaborativeProperties properties = new CollaborativeProperties();

    @InjectMocks private GenericComparisonService service;

    private User user;

    @BeforeEach
    void setUp() {
        // Inject the real properties record (not a mock) so thresholds/radius are concrete.
        service = new GenericComparisonService(productRepository, observationRepository, auditRepository,
                marketLocationService, viewerLocationService, privacyTierResolver, productTypeResolver, properties);
        var household = Household.builder().id(UUID.randomUUID()).build();
        user = User.builder().id(UUID.randomUUID()).household(household).build();
        lenient().when(viewerLocationService.resolve(any()))
                .thenReturn(new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS"));
        lenient().when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());
        // Grocery tier (relaxed) by default; thresholds come from the real properties.
        lenient().when(privacyTierResolver.isSensitiveProduct(any(UUID.class))).thenReturn(false);
        lenient().when(privacyTierResolver.thresholdsFor(false))
                .thenReturn(new PrivacyTierResolver.Thresholds(
                        properties.getCollaborative().getMinHouseholdsForGrocery(),
                        properties.getCollaborative().getMinObservationsForGrocery()));
        lenient().when(privacyTierResolver.thresholdsFor(true))
                .thenReturn(new PrivacyTierResolver.Thresholds(
                        properties.getCollaborative().getMinHouseholdsForPublic(),
                        properties.getCollaborative().getMinObservationsPerProductMarket()));
    }

    private Product product(UUID id, String brand, BigDecimal size) {
        return Product.builder().id(id).normalizedName("Creme de Leite " + brand)
                .genericName("Creme de Leite").genericNameNorm("creme de leite")
                .brand(brand).brandNorm(brand.toLowerCase())
                .packSize(size).packUnit("G").build();
    }

    private PriceObservation observation(Product product, String cnpj, String state,
                                         BigDecimal price, LocalDateTime observedAt) {
        return PriceObservation.builder()
                .product(product)
                .channel(ReceiptChannel.IN_STORE)
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName("Mercado " + cnpj.substring(0, 2))
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .city("Porto Alegre")
                .state(state)
                .build();
    }

    private ProductMarketHouseholdCount householdCount(UUID productId, String cnpj, long households) {
        return new ProductMarketHouseholdCount() {
            public UUID getProductId() { return productId; }
            public String getCnpj() { return cnpj; }
            public long getHouseholds() { return households; }
        };
    }

    @Test
    void flattensOffersCheapestFirstAndFlagsScannedProduct() {
        var scanned = UUID.randomUUID();
        var other = UUID.randomUUID();
        var nestle = product(scanned, "Nestlé", new BigDecimal("200"));
        var italac = product(other, "Italac", new BigDecimal("200"));
        when(productRepository.findById(scanned)).thenReturn(Optional.of(nestle));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(nestle, italac));
        var observations = List.of(
                observation(nestle, "11111111000100", "RS", new BigDecimal("3.49"), LocalDateTime.of(2026, 1, 5, 0, 0)),
                observation(italac, "22222222000100", "RS", new BigDecimal("2.99"), LocalDateTime.of(2026, 1, 6, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(scanned, "11111111000100", 1L),
                                    householdCount(other, "22222222000100", 1L)));

        var result = service.forProduct(scanned, user);

        assertThat(result.typeName()).isEqualTo("Creme de Leite");
        assertThat(result.scope()).isEqualTo("STATE");
        assertThat(result.offers()).hasSize(2);
        assertThat(result.offers().get(0).productId()).isEqualTo(other);
        assertThat(result.offers().get(0).unitPrice()).isEqualByComparingTo("2.99");
        assertThat(result.offers().get(0).scanned()).isFalse();
        assertThat(result.offers().get(1).productId()).isEqualTo(scanned);
        assertThat(result.offers().get(1).unitPrice()).isEqualByComparingTo("3.49");
        assertThat(result.offers().get(1).scanned()).isTrue();
        assertThat(result.offers().get(1).productName()).isEqualTo("Nestlé 200g");
    }

    @Test
    void oneOfferPerProductMarketUsingMostRecentObservation() {
        var scanned = UUID.randomUUID();
        var nestle = product(scanned, "Nestlé", new BigDecimal("200"));
        when(productRepository.findById(scanned)).thenReturn(Optional.of(nestle));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(nestle));
        // Two observations for the SAME (product, market): the most recent wins.
        var observations = List.of(
                observation(nestle, "11111111000100", "RS", new BigDecimal("4.00"), LocalDateTime.of(2026, 1, 1, 0, 0)),
                observation(nestle, "11111111000100", "RS", new BigDecimal("3.49"), LocalDateTime.of(2026, 1, 9, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(scanned, "11111111000100", 2L)));

        var result = service.forProduct(scanned, user);

        assertThat(result.offers()).hasSize(1);
        assertThat(result.offers().get(0).unitPrice()).isEqualByComparingTo("3.49");
        assertThat(result.offers().get(0).observedAt()).isEqualTo(LocalDateTime.of(2026, 1, 9, 0, 0));
    }

    @Test
    void widensFromStateToNationalWhenNoOffersInState() {
        var scanned = UUID.randomUUID();
        var nestle = product(scanned, "Nestlé", new BigDecimal("200"));
        when(productRepository.findById(scanned)).thenReturn(Optional.of(nestle));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(nestle));
        // Viewer's majority UF is RS, but the only offer is in SP → STATE empty, NATIONAL hits.
        var observations = List.of(
                observation(nestle, "33333333000100", "SP", new BigDecimal("2.79"), LocalDateTime.of(2026, 1, 3, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(scanned, "33333333000100", 1L)));

        var result = service.forProduct(scanned, user);

        assertThat(result.scope()).isEqualTo("NATIONAL");
        assertThat(result.offers()).hasSize(1);
        assertThat(result.offers().get(0).state()).isEqualTo("SP");
        assertThat(result.offers().get(0).unitPrice()).isEqualByComparingTo("2.79");
    }

    @Test
    void sensitiveProductBelowKAnonYieldsNoOffer() {
        var scanned = UUID.randomUUID();
        var remedio = Product.builder().id(scanned).normalizedName("Remédio X")
                .genericName("Remédio").genericNameNorm("remedio").brand("Marca").build();
        when(productRepository.findById(scanned)).thenReturn(Optional.of(remedio));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("remedio"))
                .thenReturn(List.of(remedio));
        when(privacyTierResolver.isSensitiveProduct(scanned)).thenReturn(true);
        // Only 1 household at this pharmacy — K=3 required for a sensitive product → blocked.
        var observations = List.of(
                observation(remedio, "44444444000100", "RS", new BigDecimal("9.90"), LocalDateTime.of(2026, 1, 2, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(scanned, "44444444000100", 1L)));

        var result = service.forProduct(scanned, user);

        assertThat(result.offers()).isEmpty();
    }

    @Test
    void pharmacyMarketKeepsFullKEvenForGroceryProduct() {
        var scanned = UUID.randomUUID();
        var product = product(scanned, "Nestlé", new BigDecimal("200"));
        when(productRepository.findById(scanned)).thenReturn(Optional.of(product));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(product));
        var pharmacy = new MarketLocation();
        pharmacy.setSegment(MerchantSegment.PHARMACY);
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of("55555555000100", pharmacy));
        var observations = List.of(
                observation(product, "55555555000100", "RS", new BigDecimal("3.49"), LocalDateTime.of(2026, 1, 2, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(scanned, "55555555000100", 1L)));

        var result = service.forProduct(scanned, user);

        // Grocery product but observed at a PHARMACY → K=3, only 1 household → blocked.
        assertThat(result.offers()).isEmpty();
    }

    @Test
    void byTypeResolvesCanonicalTypeFromNoisyCatalogNameThenFlattens() {
        var italac = UUID.randomUUID();
        var product = product(italac, "Italac", new BigDecimal("200"));
        when(productTypeResolver.typeKey("CREME DE LEITE UHT NESTLE")).thenReturn("creme de leite");
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(product));
        var observations = List.of(
                observation(product, "22222222000100", "RS", new BigDecimal("2.99"), LocalDateTime.of(2026, 1, 4, 0, 0)));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductsByMarket(anyList(), any()))
                .thenReturn(List.of(householdCount(italac, "22222222000100", 1L)));

        var result = service.forType("CREME DE LEITE UHT NESTLE", user);

        assertThat(result.typeName()).isEqualTo("Creme de Leite");
        assertThat(result.scannedProductId()).isNull();
        assertThat(result.offers()).hasSize(1);
        assertThat(result.offers().get(0).scanned()).isFalse();
        assertThat(result.offers().get(0).unitPrice()).isEqualByComparingTo("2.99");
    }

    @Test
    void byTypeReturnsEmptyWhenResolverYieldsNoType() {
        when(productTypeResolver.typeKey("???")).thenReturn(null);

        var result = service.forType("???", user);

        assertThat(result.offers()).isEmpty();
        assertThat(result.scannedProductId()).isNull();
    }

    @Test
    void honestEmptyWhenNoObservationsForType() {
        var scanned = UUID.randomUUID();
        var nestle = product(scanned, "Nestlé", new BigDecimal("200"));
        when(productRepository.findById(scanned)).thenReturn(Optional.of(nestle));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(nestle));
        when(observationRepository.findRecentByProductIds(anyList(), any())).thenReturn(List.of());

        var result = service.forProduct(scanned, user);

        assertThat(result.offers()).isEmpty();
        assertThat(result.scope()).isNull();
    }
}
