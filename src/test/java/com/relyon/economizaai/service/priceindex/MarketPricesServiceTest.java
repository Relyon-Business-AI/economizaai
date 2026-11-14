package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.exception.InvalidCnpjException;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.PriceObservationRepository.MarketProductLatestPrice;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketPricesServiceTest {

    private static final String CNPJ = "12345678000190";

    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private PriceObservationRepository observationRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryResolver categoryResolver;

    @Captor private ArgumentCaptor<LocalDateTime> sinceCaptor;
    @Captor private ArgumentCaptor<String> namePatternCaptor;
    @Captor private ArgumentCaptor<Long> minHouseholdsCaptor;
    @Captor private ArgumentCaptor<Long> minObservationsCaptor;
    @Captor private ArgumentCaptor<Long> sensitiveMinHouseholdsCaptor;
    @Captor private ArgumentCaptor<Long> sensitiveMinObservationsCaptor;
    @Captor private ArgumentCaptor<Collection<UUID>> sensitiveCategoryIdsCaptor;
    @Captor private ArgumentCaptor<Pageable> pageableCaptor;

    private CollaborativeProperties properties;
    private MarketPricesService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        var privacyTierResolver = new PrivacyTierResolver(productRepository, categoryResolver, properties);
        service = new MarketPricesService(marketLocationRepository, observationRepository, privacyTierResolver, properties);
    }

    private void marketWithSegment(MerchantSegment segment) {
        when(marketLocationRepository.findByCnpj(CNPJ))
                .thenReturn(Optional.of(MarketLocation.builder().cnpj(CNPJ).segment(segment).build()));
    }

    private void repositoryReturns(List<MarketProductLatestPrice> rows) {
        when(observationRepository.findLatestDisclosablePricesAtMarket(anyString(), any(), anyString(), anyBoolean(),
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(rows, invocation.getArgument(10), rows.size()));
    }

    private void captureQuery(boolean anyCategory, ProductCategory category) {
        verify(observationRepository).findLatestDisclosablePricesAtMarket(eq(CNPJ), sinceCaptor.capture(),
                namePatternCaptor.capture(), eq(anyCategory), eq(category),
                minHouseholdsCaptor.capture(), minObservationsCaptor.capture(),
                sensitiveMinHouseholdsCaptor.capture(), sensitiveMinObservationsCaptor.capture(),
                sensitiveCategoryIdsCaptor.capture(), pageableCaptor.capture());
    }

    private static MarketPricesService.Filter defaults() {
        return new MarketPricesService.Filter(null, null, null, null, 0, 30);
    }

    private static MarketProductLatestPrice latestPrice(UUID productId, String name, ProductCategory category,
                                                        String price, LocalDateTime observedAt) {
        var row = mock(MarketProductLatestPrice.class);
        when(row.getProductId()).thenReturn(productId);
        when(row.getProductName()).thenReturn(name);
        when(row.getCategory()).thenReturn(category);
        when(row.getLatestPrice()).thenReturn(new BigDecimal(price));
        when(row.getObservedAt()).thenReturn(observedAt);
        return row;
    }

    @Test
    void unknownMarketIs404() {
        when(marketLocationRepository.findByCnpj(CNPJ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latestPrices(CNPJ, defaults()))
                .isInstanceOf(MarketNotFoundException.class);
        verifyNoInteractions(observationRepository);
    }

    @Test
    void malformedCnpjIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.latestPrices("123", defaults()))
                .isInstanceOf(InvalidCnpjException.class);
        verifyNoInteractions(marketLocationRepository, observationRepository);
    }

    @Test
    void formattedCnpjIsNormalized() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        repositoryReturns(List.of());

        service.latestPrices("12.345.678/0001-90", defaults());

        captureQuery(true, ProductCategory.OTHER);
    }

    @Test
    void masterSwitchOffReturnsAnEmptyPageWithoutQuerying() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        properties.getCollaborative().setEnabled(false);

        var page = service.latestPrices(CNPJ, defaults());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(observationRepository);
    }

    @Test
    void groceryStoreUsesTheRelaxedTierButKeepsFullKForSensitiveProducts() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        var healthNode = UUID.randomUUID();
        when(categoryResolver.nodeAndDescendantIds("saude")).thenReturn(Set.of(healthNode));
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, defaults());

        captureQuery(true, ProductCategory.OTHER);
        assertThat(minHouseholdsCaptor.getValue()).isEqualTo(1);
        assertThat(minObservationsCaptor.getValue()).isEqualTo(1);
        assertThat(sensitiveMinHouseholdsCaptor.getValue()).isEqualTo(3);
        assertThat(sensitiveMinObservationsCaptor.getValue()).isEqualTo(3);
        assertThat(sensitiveCategoryIdsCaptor.getValue()).containsExactly(healthNode);
    }

    @Test
    void pharmacyUnknownAndUnclassifiedStoresRequireFullKForEveryProduct() {
        repositoryReturns(List.of());
        for (var segment : List.of(MerchantSegment.PHARMACY, MerchantSegment.UNKNOWN, MerchantSegment.OTHER)) {
            marketWithSegment(segment);

            service.latestPrices(CNPJ, defaults());
        }

        verify(observationRepository, times(3)).findLatestDisclosablePricesAtMarket(
                eq(CNPJ), any(), anyString(), eq(true), eq(ProductCategory.OTHER),
                eq(3L), eq(3L), eq(3L), eq(3L), any(), any());
    }

    @Test
    void sensitiveCategoryListIsNeverEmpty() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        when(categoryResolver.nodeAndDescendantIds("saude")).thenReturn(Set.of());
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, defaults());

        captureQuery(true, ProductCategory.OTHER);
        assertThat(sensitiveCategoryIdsCaptor.getValue()).isNotEmpty();
    }

    @Test
    void defaultsAreThirtyDaysRecentFirstAndNoFilters() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, defaults());

        captureQuery(true, ProductCategory.OTHER);
        assertThat(sinceCaptor.getValue()).isEqualTo(BrazilClock.today().minusDays(30).atStartOfDay());
        assertThat(namePatternCaptor.getValue()).isEqualTo("%");
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(30);
        assertThat(pageableCaptor.getValue().getSort().iterator().next())
                .isEqualTo(Sort.Order.desc("po.observedAt"));
    }

    @Test
    void windowPageAndSizeAreClamped() {
        assertThat(MarketPricesService.clampDays(null)).isEqualTo(30);
        assertThat(MarketPricesService.clampDays(0)).isEqualTo(1);
        assertThat(MarketPricesService.clampDays(-5)).isEqualTo(1);
        assertThat(MarketPricesService.clampDays(90)).isEqualTo(90);
        assertThat(MarketPricesService.clampDays(9999)).isEqualTo(180);

        marketWithSegment(MerchantSegment.SUPERMARKET);
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, new MarketPricesService.Filter(null, null, 9999, MarketPricesSort.NAME, -3, 5000));

        captureQuery(true, ProductCategory.OTHER);
        assertThat(sinceCaptor.getValue()).isEqualTo(BrazilClock.today().minusDays(180).atStartOfDay());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void queryAndCategoryFiltersAreForwarded() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, new MarketPricesService.Filter("  Arroz 100% ", ProductCategory.GROCERIES, 7,
                MarketPricesSort.CHEAPEST, 2, 10));

        captureQuery(false, ProductCategory.GROCERIES);
        assertThat(namePatternCaptor.getValue()).isEqualTo("%arroz 100!%%");
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getSort().iterator().next())
                .isEqualTo(Sort.Order.asc("po.unitPrice"));
    }

    @Test
    void nameSortOrdersByProductName() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        repositoryReturns(List.of());

        service.latestPrices(CNPJ, new MarketPricesService.Filter(null, null, null, MarketPricesSort.NAME, 0, 30));

        captureQuery(true, ProductCategory.OTHER);
        assertThat(pageableCaptor.getValue().getSort().iterator().next())
                .isEqualTo(Sort.Order.asc("product.normalizedName"));
    }

    @Test
    void rowsCarryOnlyProductPriceAndTheObservationDay() {
        marketWithSegment(MerchantSegment.SUPERMARKET);
        var productId = UUID.randomUUID();
        repositoryReturns(List.of(latestPrice(productId, "Arroz Branco 5kg", ProductCategory.GROCERIES,
                "24.9000", LocalDateTime.of(2026, 11, 10, 0, 0))));

        var page = service.latestPrices(CNPJ, defaults());

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0)).isEqualTo(new MarketProductPriceResponse(
                productId, "Arroz Branco 5kg", ProductCategory.GROCERIES, new BigDecimal("24.9000"),
                LocalDate.of(2026, 11, 10)));
    }
}
