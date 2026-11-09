package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.extraction.ProductTypeResolver;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenericComparisonServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private PriceIndexService priceIndexService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private WatchedMarketService watchedMarketService;
    @Mock private ProductTypeResolver productTypeResolver;

    @InjectMocks private GenericComparisonService service;

    private User user;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).build();
        user = User.builder().id(UUID.randomUUID()).household(household).build();
        lenient().when(viewerLocationService.resolve(any()))
                .thenReturn(new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS"));
        lenient().when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of());
    }

    private Product product(UUID id, String brand, BigDecimal size) {
        return Product.builder().id(id).normalizedName("Creme de Leite " + brand)
                .genericName("Creme de Leite").genericNameNorm("creme de leite")
                .brand(brand).brandNorm(brand.toLowerCase())
                .packSize(size).packUnit("G").build();
    }

    private MarketPriceRow row(BigDecimal price) {
        return new MarketPriceRow("93015006005182", "93015006", "Mercado A",
                price, price, price, LocalDateTime.of(2026, 1, 1, 0, 0), 5, 3L, null, false);
    }

    @Test
    void ordersPricedProductsCheapestFirstAndFlagsScanned() {
        var scanned = UUID.randomUUID();
        var cheaper = UUID.randomUUID();
        when(productRepository.findById(scanned)).thenReturn(Optional.of(product(scanned, "Nestlé", new BigDecimal("200"))));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(product(scanned, "Nestlé", new BigDecimal("200")),
                                    product(cheaper, "Italac", new BigDecimal("200"))));
        when(priceIndexService.bestMarketsForViewer(eq(scanned), anyInt(), any(), any()))
                .thenReturn(List.of(row(new BigDecimal("3.49"))));
        when(priceIndexService.bestMarketsForViewer(eq(cheaper), anyInt(), any(), any()))
                .thenReturn(List.of(row(new BigDecimal("2.99"))));

        var result = service.forProduct(scanned, user);

        assertThat(result.typeName()).isEqualTo("Creme de Leite");
        assertThat(result.products()).hasSize(2);
        assertThat(result.products().get(0).productId()).isEqualTo(cheaper);
        assertThat(result.products().get(0).price()).isEqualByComparingTo("2.99");
        assertThat(result.products().get(0).scanned()).isFalse();
        assertThat(result.products().get(1).productId()).isEqualTo(scanned);
        assertThat(result.products().get(1).scanned()).isTrue();
        assertThat(result.products().get(1).label()).isEqualTo("Nestlé 200g");
    }

    @Test
    void productsWithoutPriceSinkToBottom() {
        var scanned = UUID.randomUUID();
        var priceless = UUID.randomUUID();
        when(productRepository.findById(scanned)).thenReturn(Optional.of(product(scanned, "Nestlé", new BigDecimal("200"))));
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(product(priceless, "Amanhecer", new BigDecimal("200")),
                                    product(scanned, "Nestlé", new BigDecimal("200"))));
        when(priceIndexService.bestMarketsForViewer(eq(scanned), anyInt(), any(), any()))
                .thenReturn(List.of(row(new BigDecimal("3.49"))));
        when(priceIndexService.bestMarketsForViewer(eq(priceless), anyInt(), any(), any()))
                .thenReturn(List.of());

        var result = service.forProduct(scanned, user);

        assertThat(result.products().get(0).productId()).isEqualTo(scanned);
        assertThat(result.products().get(0).hasPrice()).isTrue();
        assertThat(result.products().get(1).productId()).isEqualTo(priceless);
        assertThat(result.products().get(1).hasPrice()).isFalse();
        assertThat(result.products().get(1).price()).isNull();
    }

    @Test
    void byTypeResolvesCanonicalTypeFromNoisyCatalogNameThenRanks() {
        // Nome cru do catálogo (Open Food Facts) → o resolver (pipeline da ingestão) devolve o
        // tipo canônico "creme de leite"; a busca por generic_name_norm então casa os produtos.
        var italac = UUID.randomUUID();
        when(productTypeResolver.typeKey("CREME DE LEITE UHT NESTLE")).thenReturn("creme de leite");
        when(productRepository.findByGenericNameNormOrderByBrandNormAscPackSizeAsc("creme de leite"))
                .thenReturn(List.of(product(italac, "Italac", new BigDecimal("200"))));
        when(priceIndexService.bestMarketsForViewer(eq(italac), anyInt(), any(), any()))
                .thenReturn(List.of(row(new BigDecimal("2.99"))));

        var result = service.forType("CREME DE LEITE UHT NESTLE", user);

        // typeName de display vem do genericName de um produto do tipo.
        assertThat(result.typeName()).isEqualTo("Creme de Leite");
        assertThat(result.scannedProductId()).isNull();
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).scanned()).isFalse();
        assertThat(result.products().get(0).price()).isEqualByComparingTo("2.99");
    }

    @Test
    void byTypeReturnsEmptyWhenResolverYieldsNoType() {
        when(productTypeResolver.typeKey("???")).thenReturn(null);

        var result = service.forType("???", user);

        assertThat(result.products()).isEmpty();
        assertThat(result.scannedProductId()).isNull();
    }

    @Test
    void productWithoutGenericTypeReturnsOnlyItself() {
        var scanned = UUID.randomUUID();
        var noType = Product.builder().id(scanned).normalizedName("Produto X")
                .genericName(null).genericNameNorm(null).build();
        when(productRepository.findById(scanned)).thenReturn(Optional.of(noType));
        when(priceIndexService.bestMarketsForViewer(eq(scanned), anyInt(), any(), any()))
                .thenReturn(List.of());

        var result = service.forProduct(scanned, user);

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).productId()).isEqualTo(scanned);
    }
}
