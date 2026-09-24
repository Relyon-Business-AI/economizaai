package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
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
import com.relyon.economizaai.service.ItemQueryService.ItemFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises GET /items (item-level slicer) end-to-end against H2. Reuses the
 * same hand-crafted dataset shape as the insights query test so the two stay
 * comparable: two markets, three categories, four confirmed receipts, 8 items.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ItemQueryServiceIntegrationTest {

    @Autowired private ItemQueryService service;
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
                .normalizedName("leite italac 1l").category(ProductCategory.MEAT_DAIRY).build());
        arroz = productRepository.save(Product.builder()
                .normalizedName("arroz tio joao 5kg").category(ProductCategory.GROCERIES).build());
        detergente = productRepository.save(Product.builder()
                .normalizedName("detergente ype 500ml").category(ProductCategory.CLEANING).build());

        receipt(household, "93015006005182", "Zaffari", LocalDateTime.of(2026, Month.APRIL, 10, 10, 0),
                List.of(item(arroz, "50.00"), item(leite, "10.00")));
        receipt(household, "93015006000111", "Bistek", LocalDateTime.of(2026, Month.APRIL, 20, 10, 0),
                List.of(item(detergente, "5.00"), item(arroz, "40.00")));
        receipt(household, "93015006005182", "Zaffari", LocalDateTime.of(2026, Month.MAY, 3, 10, 0),
                List.of(item(leite, "12.00")));
        receipt(household, "93015006000111", "Bistek", LocalDateTime.of(2026, Month.MAY, 15, 10, 0),
                List.of(item(arroz, "30.00"), item(leite, "8.00"), item(detergente, "6.00")));
    }

    @Test
    void unfiltered_returnsAllConfirmedItems_newestFirst() {
        var page = service.query(user, filters(), PageRequest.of(0, 20));
        assertEquals(8, page.getTotalElements());
        assertEquals(8, page.getContent().size());
        // newest receipt is 2026-05-15
        assertEquals(LocalDateTime.of(2026, Month.MAY, 15, 10, 0), page.getContent().get(0).purchasedAt());
    }

    @Test
    void filter_byCategory_returnsOnlyThatCategorysItems() {
        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.MEAT_DAIRY), null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(3, page.getTotalElements()); // leite bought 3 times
        assertTrue(page.getContent().stream()
                .allMatch(i -> "MEAT_DAIRY".equals(i.category())));
        // With no override, effective category and global category coincide.
        assertTrue(page.getContent().stream()
                .allMatch(i -> "MEAT_DAIRY".equals(i.globalCategory())));
    }

    @Test
    void householdLens_excludesProductMovedToCustomCategory_fromItsOldEnumFilter() {
        // Migrate "leite" (global MEAT_DAIRY) into a custom category.
        var custom = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(user.getHousehold()).name("Laticinios").build());
        categoryOverrideService.setCustomOverride(user, leite, custom);

        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.MEAT_DAIRY), null, null, null, null),
                PageRequest.of(0, 20));

        // Household lens: leite no longer counts under MEAT_DAIRY (moved out).
        assertEquals(0, page.getTotalElements());
    }

    @Test
    void householdLens_includesProductOverriddenIntoTheFilteredEnum() {
        // Move "leite" from MEAT_DAIRY to GROCERIES via an enum override.
        categoryOverrideService.setOverride(user, leite, ProductCategory.GROCERIES);

        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.GROCERIES), null, null, null, null),
                PageRequest.of(0, 20));

        // arroz (3, global GROCERIES) + leite (3, overridden into GROCERIES) = 6.
        assertEquals(6, page.getTotalElements());
        assertTrue(page.getContent().stream()
                .allMatch(item -> "GROCERIES".equals(item.category())));
    }

    @Test
    void globalLens_stillIncludesProductMovedToCustomCategory() {
        var custom = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(user.getHousehold()).name("Laticinios").build());
        categoryOverrideService.setCustomOverride(user, leite, custom);

        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.MEAT_DAIRY), null, null, null, null, CategoryView.GLOBAL),
                PageRequest.of(0, 20));

        // GLOBAL lens ignores the override → leite still counts under MEAT_DAIRY.
        assertEquals(3, page.getTotalElements());
        // category reflects the override label, globalCategory reflects the enum.
        var row = page.getContent().get(0);
        assertEquals("Laticinios", row.category());
        assertEquals("MEAT_DAIRY", row.globalCategory());
    }

    @Test
    void filter_byMultipleCategories_orsThemTogether() {
        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.GROCERIES, ProductCategory.CLEANING), null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(5, page.getTotalElements()); // arroz 3 + detergente 2
    }

    @Test
    void filter_byMarket_narrows() {
        var page = service.query(user,
                ItemFilters.fromRequest(null, null, List.of("93015006005182"), null,
                        null, null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(3, page.getTotalElements()); // Zaffari: arroz, leite (Apr) + leite (May)
    }

    @Test
    void filter_byProductId() {
        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null, null,
                        List.of(arroz.getId()), null, null, null),
                PageRequest.of(0, 20));
        assertEquals(3, page.getTotalElements());
    }

    @Test
    void filter_byDateRange_isInclusive() {
        var page = service.query(user,
                ItemFilters.fromRequest(LocalDateTime.of(2026, Month.APRIL, 1, 0, 0),
                        LocalDateTime.of(2026, Month.APRIL, 30, 23, 59, 59),
                        null, null, null, null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(4, page.getTotalElements()); // April: 2 + 2 items
    }

    @Test
    void pagination_limitsRowsButReportsFullTotal() {
        var page = service.query(user, filters(), PageRequest.of(0, 2));
        assertEquals(8, page.getTotalElements());
        assertEquals(2, page.getContent().size());
        assertEquals(4, page.getTotalPages());
    }

    @Test
    void row_carriesReceiptContextAndDisplayDescription() {
        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.MEAT_DAIRY), null, null, null, null),
                PageRequest.of(0, 1));
        var row = page.getContent().get(0);
        assertNotNull(row.receiptId());
        assertNotNull(row.marketName());
        assertEquals("93015006005182".length(), row.marketCnpj().length());
        assertEquals(leite.getId(), row.productId());
        assertEquals("leite italac 1l", row.displayDescription());
    }

    @Test
    void excludedItems_areDropped() {
        var household = user.getHousehold();
        var receipt = Receipt.builder()
                .user(user).household(household).chaveAcesso(uniqueChave())
                .uf(UnidadeFederativa.RS).cnpjEmitente("93015006005182").marketName("Zaffari")
                .issuedAt(LocalDateTime.of(2026, Month.JUNE, 1, 10, 0))
                .totalAmount(new BigDecimal("9.00")).qrPayload("test")
                .status(ReceiptStatus.CONFIRMED).confirmedAt(LocalDateTime.of(2026, Month.JUNE, 1, 10, 0))
                .build();
        var excluded = item(leite, "9.00");
        excluded.setExcluded(true);
        receipt.addItem(excluded);
        receiptRepository.save(receipt);

        var page = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.MEAT_DAIRY), null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(3, page.getTotalElements()); // still 3 — the excluded leite isn't counted
    }

    @Test
    void filter_byOther_includesUnmatchedItemsWithNoProduct() {
        // Unmatched line item (no linked product) — the insights breakdown buckets it as
        // OTHER, so ?category=OTHER must return it too (regression: it returned empty before).
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold()).chaveAcesso(uniqueChave())
                .uf(UnidadeFederativa.RS).cnpjEmitente("93015006005182").marketName("Zaffari")
                .issuedAt(LocalDateTime.of(2026, Month.JUNE, 2, 10, 0))
                .totalAmount(new BigDecimal("7.00")).qrPayload("test")
                .status(ReceiptStatus.CONFIRMED).confirmedAt(LocalDateTime.of(2026, Month.JUNE, 2, 10, 0))
                .build();
        receipt.addItem(ReceiptItem.builder()
                .product(null).lineNumber(1).rawDescription("PRODUTO DESCONHECIDO")
                .quantity(BigDecimal.ONE).unit("UN")
                .unitPrice(new BigDecimal("7.00")).totalPrice(new BigDecimal("7.00"))
                .build());
        receiptRepository.save(receipt);

        var other = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.OTHER), null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(1, other.getTotalElements());
        assertNull(other.getContent().get(0).productId());

        // GLOBAL lens behaves the same.
        var otherGlobal = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.OTHER), null, null, null, null, CategoryView.GLOBAL),
                PageRequest.of(0, 20));
        assertEquals(1, otherGlobal.getTotalElements());

        // A non-OTHER filter must NOT pick up the unmatched item.
        var groceries = service.query(user,
                ItemFilters.fromRequest(null, null, null, null,
                        List.of(ProductCategory.GROCERIES), null, null, null, null),
                PageRequest.of(0, 20));
        assertEquals(3, groceries.getTotalElements()); // arroz x3 only
    }

    @Test
    void scope_supportedReturnsOnlyGroceryPharmacyMarkets() {
        seedSegments(); // Zaffari = SUPERMARKET (supported), Bistek = OTHER

        var supported = service.query(user, filtersScope(MarketScope.SUPPORTED), PageRequest.of(0, 20));
        // Zaffari items only: arroz 50, leite 10, leite 12
        assertEquals(3, supported.getTotalElements());
        assertTrue(supported.getContent().stream()
                .allMatch(row -> "93015006005182".equals(row.marketCnpj())));
    }

    @Test
    void scope_otherReturnsEverythingElse() {
        seedSegments();

        var other = service.query(user, filtersScope(MarketScope.OTHER), PageRequest.of(0, 20));
        // Bistek items only: detergente 5, arroz 40, arroz 30, leite 8, detergente 6
        assertEquals(5, other.getTotalElements());
        assertTrue(other.getContent().stream()
                .allMatch(row -> "93015006000111".equals(row.marketCnpj())));
    }

    @Test
    void scope_allReturnsBothSegments() {
        seedSegments();

        var all = service.query(user, filtersScope(MarketScope.ALL), PageRequest.of(0, 20));
        assertEquals(8, all.getTotalElements());
    }

    @Test
    void scope_supportedWithNoSupportedMarkets_returnsEmpty() {
        // No MarketLocation rows at all → nothing is "supported" → SUPPORTED yields no rows
        // (and must not throw on an empty IN list).
        var supported = service.query(user, filtersScope(MarketScope.SUPPORTED), PageRequest.of(0, 20));
        assertEquals(0, supported.getTotalElements());
    }

    private void seedSegments() {
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006005182").cnpjRoot("93015006").segment(MerchantSegment.SUPERMARKET).build());
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006000111").cnpjRoot("93015006").segment(MerchantSegment.OTHER).build());
    }

    private ItemFilters filtersScope(MarketScope scope) {
        return ItemFilters.fromRequest(null, null, null, null, null, null, null, null, null,
                CategoryView.HOUSEHOLD, scope);
    }

    private ItemFilters filters() {
        return ItemFilters.fromRequest(null, null, null, null, null, null, null, null, null);
    }

    private void receipt(Household household, String cnpj, String marketName,
                         LocalDateTime issuedAt, List<ReceiptItem> items) {
        var receipt = Receipt.builder()
                .user(user).household(household).chaveAcesso(uniqueChave())
                .uf(UnidadeFederativa.RS).cnpjEmitente(cnpj).marketName(marketName)
                .issuedAt(issuedAt)
                .totalAmount(items.stream().map(ReceiptItem::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .qrPayload("test").status(ReceiptStatus.CONFIRMED).confirmedAt(issuedAt)
                .build();
        items.forEach(receipt::addItem);
        receiptRepository.save(receipt);
    }

    private ReceiptItem item(Product product, String totalPrice) {
        var amount = new BigDecimal(totalPrice);
        return ReceiptItem.builder()
                .product(product).lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE).unit("UN")
                .unitPrice(amount).totalPrice(amount)
                .build();
    }

    private static String uniqueCode() {
        return ("X" + UUID.randomUUID().toString().substring(0, 5)).toUpperCase();
    }

    private static String uniqueChave() {
        var digits = UUID.randomUUID().toString().replace("-", "").replaceAll("[^0-9]", "0");
        return (digits + "00000000000000000000000000000000000000000000").substring(0, 44);
    }
}
