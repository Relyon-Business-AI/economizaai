package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.InsightsGroupBy;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.InsightsQueryService.QueryFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the flexible /insights/query slicer end-to-end against the in-memory
 * H2 datasource. Plants a hand-crafted dataset (two markets, three categories,
 * three products, four confirmed receipts) so we can assert on the filter +
 * groupBy combinations the FE will rely on.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InsightsQueryServiceIntegrationTest {

    @Autowired private InsightsQueryService service;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Autowired private HouseholdCustomCategoryRepository customCategoryRepository;
    @Autowired private MarketLocationRepository marketLocationRepository;

    private User user;
    private Product leite;
    private Product arroz;
    private Product detergente;

    @BeforeEach
    void seedHistory() {
        var household = householdRepository.save(Household.builder().inviteCode(uniqueCode()).build());
        user = userRepository.save(User.builder()
                .name("Maria").email("maria-" + System.nanoTime() + "@e.test")
                .password("x").household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.now())
                .build());

        leite = productRepository.save(Product.builder()
                .normalizedName("leite italac 1l")
                .category(ProductCategory.MEAT_DAIRY).build());
        arroz = productRepository.save(Product.builder()
                .normalizedName("arroz tio joao 5kg")
                .category(ProductCategory.GROCERIES).build());
        detergente = productRepository.save(Product.builder()
                .normalizedName("detergente ype 500ml")
                .category(ProductCategory.CLEANING).build());

        // Two markets, four receipts spread across two months.
        // Zaffari April: arroz 50, leite 10. Total 60.
        // Bistek April:  detergente 5, arroz 40. Total 45.
        // Zaffari May:   leite 12. Total 12.
        // Bistek May:    arroz 30, leite 8, detergente 6. Total 44.
        receipt(household, "93015006005182", "Zaffari", LocalDateTime.of(2026, Month.APRIL, 10, 10, 0),
                List.of(item(arroz, "50.00"), item(leite, "10.00")));
        receipt(household, "93015006000111", "Bistek",  LocalDateTime.of(2026, Month.APRIL, 20, 10, 0),
                List.of(item(detergente, "5.00"), item(arroz, "40.00")));
        receipt(household, "93015006005182", "Zaffari", LocalDateTime.of(2026, Month.MAY,  3, 10, 0),
                List.of(item(leite, "12.00")));
        receipt(household, "93015006000111", "Bistek",  LocalDateTime.of(2026, Month.MAY, 15, 10, 0),
                List.of(item(arroz, "30.00"), item(leite, "8.00"), item(detergente, "6.00")));
    }

    @Test
    void summary_unfilteredCoversWholeHistory() {
        var result = service.query(user, filters().groupBy(InsightsGroupBy.NONE).build());

        assertEquals(0, new BigDecimal("161.00").compareTo(result.summary().total()));
        assertEquals(4, result.summary().receiptCount());
        assertEquals(8, result.summary().itemCount());
        assertTrue(result.buckets().isEmpty(), "NONE groupBy returns no buckets");
    }

    @Test
    void filter_byMarket_narrowsSummary() {
        var result = service.query(user,
                filters().marketCnpjs(List.of("93015006005182")).build());
        assertEquals(0, new BigDecimal("72.00").compareTo(result.summary().total()),
                "Zaffari only: 60 (April) + 12 (May) = 72");
        assertEquals(2, result.summary().receiptCount());
    }

    @Test
    void filter_byMultipleCategories_orsThemTogether() {
        var result = service.query(user,
                filters().categories(List.of(ProductCategory.GROCERIES, ProductCategory.CLEANING)).build());
        // GROCERIES (arroz) 120 + CLEANING (detergente) 11 = 131
        assertEquals(0, new BigDecimal("131.00").compareTo(result.summary().total()));
    }

    @Test
    void filter_byMultipleMarkets_orsThemTogether() {
        var result = service.query(user,
                filters().marketCnpjs(List.of("93015006005182", "93015006000111")).build());
        assertEquals(0, new BigDecimal("161.00").compareTo(result.summary().total()),
                "both markets explicitly listed = full history");
    }

    @Test
    void filter_byReceiptTotalRange_min() {
        // Receipt totals: 60, 45, 12, 44. Filter min=50 → only the 60-receipt.
        var result = service.query(user,
                filters().minReceiptTotal(new BigDecimal("50.00")).build());
        assertEquals(1, result.summary().receiptCount());
        assertEquals(0, new BigDecimal("60.00").compareTo(result.summary().total()));
    }

    @Test
    void filter_byReceiptTotalRange_minAndMax() {
        // Filter [40, 50] → keeps the 45 and 44 receipts (45 + 44 = 89).
        var result = service.query(user,
                filters().minReceiptTotal(new BigDecimal("40.00"))
                        .maxReceiptTotal(new BigDecimal("50.00")).build());
        assertEquals(2, result.summary().receiptCount());
        assertEquals(0, new BigDecimal("89.00").compareTo(result.summary().total()));
    }

    @Test
    void groupBy_market_rankedBySpend() {
        var result = service.query(user, filters().groupBy(InsightsGroupBy.MARKET).build());
        assertEquals(2, result.buckets().size());
        assertEquals("93015006000111", result.buckets().get(0).key());
        assertEquals("Bistek", result.buckets().get(0).label());
        assertEquals(0, new BigDecimal("89.00").compareTo(result.buckets().get(0).total()));
        assertEquals("93015006005182", result.buckets().get(1).key());
    }

    @Test
    void groupBy_chain_collapsesToCnpjRoot() {
        var result = service.query(user, filters().groupBy(InsightsGroupBy.CHAIN).build());
        assertEquals(1, result.buckets().size());
        assertEquals("93015006", result.buckets().get(0).key());
        assertEquals(0, new BigDecimal("161.00").compareTo(result.buckets().get(0).total()));
    }

    @Test
    void groupBy_category_globalLens_rankedBySpend() {
        var result = service.query(user,
                filters().groupBy(InsightsGroupBy.CATEGORY).categoryView(CategoryView.GLOBAL).build());
        assertEquals(3, result.buckets().size());
        assertEquals("GROCERIES", result.buckets().get(0).key());
        assertEquals(0, new BigDecimal("120.00").compareTo(result.buckets().get(0).total()));
    }

    @Test
    void groupBy_category_householdLens_isDefault_andMatchesGlobalWithoutOverrides() {
        var result = service.query(user, filters().groupBy(InsightsGroupBy.CATEGORY).build());
        assertEquals(3, result.buckets().size());
        assertEquals("GROCERIES", result.buckets().get(0).key());
        assertEquals(0, new BigDecimal("120.00").compareTo(result.buckets().get(0).total()));
    }

    @Test
    void groupBy_category_householdLens_reaggregatesOverriddenEnum_noDoubleCount() {
        // Move leite (MEAT_DAIRY, 30) into GROCERIES via an enum override.
        categoryOverrideService.setOverride(user, leite, ProductCategory.GROCERIES);

        var result = service.query(user, filters().groupBy(InsightsGroupBy.CATEGORY).build());

        var groceries = result.buckets().stream()
                .filter(bucket -> "GROCERIES".equals(bucket.key())).findFirst().orElseThrow();
        // arroz 120 + leite 30 = 150; MEAT_DAIRY bucket disappears.
        assertEquals(0, new BigDecimal("150.00").compareTo(groceries.total()));
        assertTrue(result.buckets().stream().noneMatch(bucket -> "MEAT_DAIRY".equals(bucket.key())));
        // GLOBAL lens still splits them.
        var global = service.query(user,
                filters().groupBy(InsightsGroupBy.CATEGORY).categoryView(CategoryView.GLOBAL).build());
        assertTrue(global.buckets().stream().anyMatch(bucket -> "MEAT_DAIRY".equals(bucket.key())));
    }

    @Test
    void groupBy_category_householdLens_customCategoryBecomesItsOwnBucket() {
        var custom = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(user.getHousehold()).name("Laticinios").build());
        categoryOverrideService.setCustomOverride(user, leite, custom);

        var result = service.query(user, filters().groupBy(InsightsGroupBy.CATEGORY).build());

        var laticinios = result.buckets().stream()
                .filter(bucket -> "Laticinios".equals(bucket.key())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("30.00").compareTo(laticinios.total()));
        assertTrue(result.buckets().stream().noneMatch(bucket -> "MEAT_DAIRY".equals(bucket.key())));
    }

    @Test
    void groupBy_month_orderedAscending() {
        var result = service.query(user, filters().groupBy(InsightsGroupBy.MONTH).build());
        assertEquals(2, result.buckets().size());
        assertEquals("2026-04", result.buckets().get(0).key());
        assertEquals(0, new BigDecimal("105.00").compareTo(result.buckets().get(0).total()));
        assertEquals("2026-05", result.buckets().get(1).key());
        assertEquals(0, new BigDecimal("56.00").compareTo(result.buckets().get(1).total()));
    }

    @Test
    void groupBy_product_returnsTopN() {
        var result = service.query(user,
                filters().groupBy(InsightsGroupBy.PRODUCT).limit(10).build());
        assertEquals(3, result.buckets().size());
        assertEquals("arroz tio joao 5kg", result.buckets().get(0).label());
        assertEquals(0, new BigDecimal("120.00").compareTo(result.buckets().get(0).total()));
    }

    @Test
    void filter_combinesMarketPlusCategory() {
        var result = service.query(user,
                filters().marketCnpjs(List.of("93015006000111"))
                        .categories(List.of(ProductCategory.GROCERIES)).build());
        assertEquals(0, new BigDecimal("70.00").compareTo(result.summary().total()),
                "Bistek + GROCERIES: arroz 40 (Apr) + arroz 30 (May) = 70");
    }

    @Test
    void filter_byDateRange_isInclusive() {
        var result = service.query(user,
                filters().from(LocalDateTime.of(2026, Month.APRIL, 1, 0, 0))
                        .to(LocalDateTime.of(2026, Month.APRIL, 30, 23, 59, 59)).build());
        assertEquals(0, new BigDecimal("105.00").compareTo(result.summary().total()),
                "April only: 60 + 45 = 105");
    }

    @Test
    void averageTicket_computedFromTotalAndReceiptCount() {
        var result = service.query(user, filters().build());
        assertEquals(0, new BigDecimal("40.25").compareTo(result.summary().averageTicket()));
    }

    @Test
    void filter_echoedBackInResponse() {
        var result = service.query(user,
                filters().marketCnpjs(List.of("93015006005182"))
                        .categories(List.of(ProductCategory.MEAT_DAIRY)).build());
        assertEquals(List.of("93015006005182"), result.filters().marketCnpjs());
        assertEquals(List.of(ProductCategory.MEAT_DAIRY), result.filters().categories());
    }

    @Test
    void scope_supportedSummary_coversOnlyGroceryPharmacyMarkets() {
        seedSegments(); // Zaffari = SUPERMARKET (supported), Bistek = OTHER

        var result = service.query(user, filters().scope(MarketScope.SUPPORTED).build());
        // Zaffari only: 60 (April) + 12 (May) = 72
        assertEquals(0, new BigDecimal("72.00").compareTo(result.summary().total()));
        assertEquals(2, result.summary().receiptCount());
    }

    @Test
    void scope_otherSummary_coversEverythingElse() {
        seedSegments();

        var result = service.query(user, filters().scope(MarketScope.OTHER).build());
        // Bistek only: 45 (April) + 44 (May) = 89
        assertEquals(0, new BigDecimal("89.00").compareTo(result.summary().total()));
        assertEquals(2, result.summary().receiptCount());
    }

    @Test
    void scope_allSummary_coversBothSegments() {
        seedSegments();

        var result = service.query(user, filters().scope(MarketScope.ALL).build());
        assertEquals(0, new BigDecimal("161.00").compareTo(result.summary().total()));
    }

    @Test
    void scope_supportedWithNoSupportedMarkets_summaryIsZero() {
        // No MarketLocation rows → nothing supported → SUPPORTED total is 0, not an IN () error.
        var result = service.query(user, filters().scope(MarketScope.SUPPORTED).build());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.summary().total()));
        assertEquals(0, result.summary().receiptCount());
    }

    private void seedSegments() {
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006005182").cnpjRoot("93015006").segment(MerchantSegment.SUPERMARKET).build());
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006000111").cnpjRoot("93015006").segment(MerchantSegment.OTHER).build());
    }

    private void receipt(Household household, String cnpj, String marketName,
                         LocalDateTime issuedAt, List<ReceiptItem> items) {
        var receipt = Receipt.builder()
                .user(user).household(household)
                .chaveAcesso(uniqueChave())
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName(marketName)
                .issuedAt(issuedAt)
                .totalAmount(items.stream().map(ReceiptItem::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .qrPayload("test")
                .status(ReceiptStatus.CONFIRMED)
                .confirmedAt(issuedAt)
                .build();
        items.forEach(receipt::addItem);
        receiptRepository.save(receipt);
    }

    private ReceiptItem item(Product product, String totalPrice) {
        var amount = new BigDecimal(totalPrice);
        return ReceiptItem.builder()
                .product(product)
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE).unit("UN")
                .unitPrice(amount).totalPrice(amount)
                .build();
    }

    /** Tiny builder so each test reads as a list of overrides on a sane default. */
    private static FilterBuilder filters() {
        return new FilterBuilder();
    }

    private static String uniqueCode() {
        return ("X" + UUID.randomUUID().toString().substring(0, 5)).toUpperCase();
    }

    private static String uniqueChave() {
        var digits = UUID.randomUUID().toString().replace("-", "").replaceAll("[^0-9]", "0");
        return (digits + "00000000000000000000000000000000000000000000").substring(0, 44);
    }

    /**
     * Mutable builder for {@link QueryFilters} so test cases override only the
     * dimensions they care about. Defaults: no filters, groupBy=NONE.
     */
    private static final class FilterBuilder {
        private LocalDateTime from;
        private LocalDateTime to;
        private List<String> marketCnpjs;
        private List<String> marketCnpjRoots;
        private List<ProductCategory> categories;
        private List<UUID> productIds;
        private List<String> eans;
        private BigDecimal minReceiptTotal;
        private BigDecimal maxReceiptTotal;
        private InsightsGroupBy groupBy = InsightsGroupBy.NONE;
        private Integer limit;
        private CategoryView categoryView = CategoryView.HOUSEHOLD;
        private MarketScope scope = MarketScope.ALL;

        FilterBuilder from(LocalDateTime v) { this.from = v; return this; }
        FilterBuilder to(LocalDateTime v) { this.to = v; return this; }
        FilterBuilder marketCnpjs(List<String> v) { this.marketCnpjs = v; return this; }
        FilterBuilder categories(List<ProductCategory> v) { this.categories = v; return this; }
        FilterBuilder minReceiptTotal(BigDecimal v) { this.minReceiptTotal = v; return this; }
        FilterBuilder maxReceiptTotal(BigDecimal v) { this.maxReceiptTotal = v; return this; }
        FilterBuilder groupBy(InsightsGroupBy v) { this.groupBy = v; return this; }
        FilterBuilder limit(Integer v) { this.limit = v; return this; }
        FilterBuilder categoryView(CategoryView v) { this.categoryView = v; return this; }
        FilterBuilder scope(MarketScope v) { this.scope = v; return this; }

        QueryFilters build() {
            return QueryFilters.fromRequest(from, to, marketCnpjs, marketCnpjRoots, categories,
                    productIds, eans, minReceiptTotal, maxReceiptTotal, groupBy, limit, categoryView, scope);
        }
    }
}
