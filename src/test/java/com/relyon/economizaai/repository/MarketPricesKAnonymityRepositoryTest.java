package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationRepository.MarketProductLatestPrice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-level proof that {@code GET /markets/{cnpj}/prices} honors k-anonymity BY TIER inside the
 * database ({@code HAVING} over distinct households, per product): a grocery product at a grocery
 * store is disclosed from one household; a health product, or anything at a non-grocery store,
 * needs the full K. The mock-based service test can't exercise the JPQL.
 */
@DataJpaTest
@ActiveProfiles("test")
class MarketPricesKAnonymityRepositoryTest {

    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private PriceObservationAuditRepository auditRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;

    private static final String STORE = "12345678000190";
    private static final String OTHER_STORE = "98765432000110";
    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 5, 1, 0, 0);
    private static final LocalDateTime DAY_ONE = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime DAY_TWO = LocalDateTime.of(2026, 6, 2, 0, 0);
    private static final long FULL_K = 3;
    private static final UUID HEALTH_NODE = UUID.randomUUID();
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 30,
            Sort.by(Sort.Order.asc("product.normalizedName"), Sort.Order.asc("product.id")));

    private int householdSequence;

    @Test
    void groceryProductAtGroceryStoreIsDisclosedFromASingleHousehold() {
        var rice = product("Arroz", ProductCategory.GROCERIES, null);
        observe(rice, STORE, "10.00", DAY_ONE, household());

        var rows = groceryStorePrices().getContent();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProductId()).isEqualTo(rice.getId());
        assertThat(rows.get(0).getProductName()).isEqualTo("Arroz");
        assertThat(rows.get(0).getCategory()).isEqualTo(ProductCategory.GROCERIES);
        assertThat(rows.get(0).getLatestPrice()).isEqualByComparingTo("10.00");
        assertThat(rows.get(0).getObservedAt()).isEqualTo(DAY_ONE);
    }

    @Test
    void uncategorizedProductStaysGrocery() {
        var uncategorized = product("Item sem categoria", null, null);
        observe(uncategorized, STORE, "3.00", DAY_ONE, household());

        assertThat(productIds(groceryStorePrices())).containsExactly(uncategorized.getId());
    }

    @Test
    void healthProductBelowKIsNotReturnedEvenAtAGroceryStore() {
        var legacyHealth = product("Dipirona", ProductCategory.HEALTH, null);
        var healthNode = product("Vitamina C", ProductCategory.OTHER, HEALTH_NODE);
        observe(legacyHealth, STORE, "8.00", DAY_ONE, household());
        observe(legacyHealth, STORE, "8.50", DAY_ONE, household());
        observe(healthNode, STORE, "20.00", DAY_ONE, household());

        assertThat(groceryStorePrices().getContent()).isEmpty();
    }

    @Test
    void healthProductIsReturnedOnceKDistinctHouseholdsObservedIt() {
        var medicine = product("Dipirona", ProductCategory.HEALTH, HEALTH_NODE);
        observe(medicine, STORE, "8.00", DAY_ONE, household());
        observe(medicine, STORE, "8.50", DAY_ONE, household());
        observe(medicine, STORE, "9.00", DAY_TWO, household());

        var rows = groceryStorePrices().getContent();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLatestPrice()).isEqualByComparingTo("9.00");
    }

    @Test
    void repeatedObservationsOfOneHouseholdCountAsOneHousehold() {
        var medicine = product("Dipirona", ProductCategory.HEALTH, null);
        var loyalHousehold = household();
        observe(medicine, STORE, "8.00", DAY_ONE, loyalHousehold);
        observe(medicine, STORE, "8.10", DAY_ONE, loyalHousehold);
        observe(medicine, STORE, "8.20", DAY_TWO, loyalHousehold);
        observe(medicine, STORE, "8.30", DAY_TWO, household());

        assertThat(groceryStorePrices().getContent()).isEmpty();
    }

    @Test
    void nonGroceryStoreKeepsFullKForEveryProduct() {
        var rice = product("Arroz", ProductCategory.GROCERIES, null);
        var soap = product("Sabonete", ProductCategory.PERSONAL_CARE, null);
        observe(rice, STORE, "10.00", DAY_ONE, household());
        observe(rice, STORE, "10.50", DAY_ONE, household());
        observe(soap, STORE, "2.00", DAY_ONE, household());
        observe(soap, STORE, "2.10", DAY_ONE, household());
        observe(soap, STORE, "2.20", DAY_TWO, household());

        var rows = sensitiveStorePrices();

        assertThat(productIds(rows)).containsExactly(soap.getId());
    }

    @Test
    void householdsOfOtherStoresChannelsOutliersAndOlderWindowsDoNotCountTowardsK() {
        var medicine = product("Dipirona", ProductCategory.HEALTH, null);
        observe(medicine, STORE, "8.00", DAY_ONE, household());
        observe(medicine, OTHER_STORE, "8.00", DAY_ONE, household());
        observe(medicine, STORE, "8.00", SINCE.minusDays(1), household());
        observeWith(medicine, STORE, "8.00", DAY_ONE, household(), ReceiptChannel.ONLINE, false);
        observeWith(medicine, STORE, "99.00", DAY_ONE, household(), ReceiptChannel.IN_STORE, true);

        assertThat(groceryStorePrices().getContent()).isEmpty();
    }

    @Test
    void observationWithoutAuditRowIsNeverDisclosed() {
        var rice = product("Arroz", ProductCategory.GROCERIES, null);
        observationRepository.save(observation(rice, STORE, "10.00", DAY_ONE, ReceiptChannel.IN_STORE, false));

        assertThat(groceryStorePrices().getContent()).isEmpty();
    }

    @Test
    void returnsOneRowPerProductWithTheMostRecentObservation() {
        var rice = product("Arroz", ProductCategory.GROCERIES, null);
        observe(rice, STORE, "10.00", DAY_ONE, household());
        observe(rice, STORE, "12.00", DAY_TWO, household());
        observe(rice, STORE, "11.00", DAY_TWO, household());
        observe(rice, OTHER_STORE, "5.00", DAY_TWO.plusDays(1), household());
        observeWith(rice, STORE, "1.00", DAY_TWO.plusDays(1), household(), ReceiptChannel.ONLINE, false);
        observeWith(rice, STORE, "99.00", DAY_TWO.plusDays(1), household(), ReceiptChannel.IN_STORE, true);

        var rows = groceryStorePrices();

        assertThat(rows.getTotalElements()).isEqualTo(1);
        assertThat(rows.getContent().get(0).getObservedAt()).isEqualTo(DAY_TWO);
        assertThat(rows.getContent().get(0).getLatestPrice()).isIn(new BigDecimal("11.0000"), new BigDecimal("12.0000"));
    }

    @Test
    void filtersByNameAndCategory() {
        var rice = product("Arroz Branco", ProductCategory.GROCERIES, null);
        var juice = product("Suco de Uva", ProductCategory.BEVERAGES, null);
        observe(rice, STORE, "10.00", DAY_ONE, household());
        observe(juice, STORE, "7.00", DAY_ONE, household());

        var byName = prices("%arroz%", true, ProductCategory.OTHER, 1, FIRST_PAGE);
        var byCategory = prices("%", false, ProductCategory.BEVERAGES, 1, FIRST_PAGE);

        assertThat(productIds(byName)).containsExactly(rice.getId());
        assertThat(productIds(byCategory)).containsExactly(juice.getId());
    }

    @Test
    void sortsAndPages() {
        var rice = product("Arroz", ProductCategory.GROCERIES, null);
        var beans = product("Feijao", ProductCategory.GROCERIES, null);
        var juice = product("Suco", ProductCategory.BEVERAGES, null);
        observe(rice, STORE, "10.00", DAY_ONE, household());
        observe(beans, STORE, "6.00", DAY_TWO, household());
        observe(juice, STORE, "7.00", DAY_ONE, household());
        var byName = Sort.by(Sort.Order.asc("product.normalizedName"), Sort.Order.asc("product.id"));
        var recentFirst = Sort.by(Sort.Order.desc("po.observedAt")).and(byName);
        var cheapestFirst = Sort.by(Sort.Order.asc("po.unitPrice")).and(byName);

        assertThat(productIds(prices("%", true, ProductCategory.OTHER, 1, PageRequest.of(0, 30, recentFirst))))
                .containsExactly(beans.getId(), rice.getId(), juice.getId());
        assertThat(productIds(prices("%", true, ProductCategory.OTHER, 1, PageRequest.of(0, 30, cheapestFirst))))
                .containsExactly(beans.getId(), juice.getId(), rice.getId());
        assertThat(productIds(prices("%", true, ProductCategory.OTHER, 1, PageRequest.of(0, 30, byName))))
                .containsExactly(rice.getId(), beans.getId(), juice.getId());

        var secondPage = prices("%", true, ProductCategory.OTHER, 1, PageRequest.of(1, 2, byName));
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(productIds(secondPage)).containsExactly(juice.getId());
    }

    private Page<MarketProductLatestPrice> groceryStorePrices() {
        return prices("%", true, ProductCategory.OTHER, 1, FIRST_PAGE);
    }

    private Page<MarketProductLatestPrice> sensitiveStorePrices() {
        return prices("%", true, ProductCategory.OTHER, FULL_K, FIRST_PAGE);
    }

    private Page<MarketProductLatestPrice> prices(String namePattern, boolean anyCategory, ProductCategory category,
                                                 long storeMinimum, Pageable pageable) {
        return observationRepository.findLatestDisclosablePricesAtMarket(STORE, SINCE, namePattern, anyCategory,
                category, storeMinimum, storeMinimum, FULL_K, FULL_K, Set.of(HEALTH_NODE), pageable);
    }

    private static List<UUID> productIds(Page<MarketProductLatestPrice> rows) {
        return rows.getContent().stream().map(MarketProductLatestPrice::getProductId).toList();
    }

    private Product product(String name, ProductCategory category, UUID categoryId) {
        return productRepository.save(Product.builder()
                .normalizedName(name).category(category).categoryId(categoryId).build());
    }

    private Household household() {
        householdSequence++;
        return householdRepository.save(Household.builder().inviteCode("MP" + householdSequence).build());
    }

    private void observe(Product product, String cnpj, String price, LocalDateTime observedAt, Household household) {
        observeWith(product, cnpj, price, observedAt, household, ReceiptChannel.IN_STORE, false);
    }

    /** Persist one observation + its audit row (audit needs a real receipt for the FK). */
    private void observeWith(Product product, String cnpj, String price, LocalDateTime observedAt,
                             Household household, ReceiptChannel channel, boolean outlier) {
        var receipt = receiptRepository.save(receiptFor(household, cnpj, channel, observedAt));
        var observation = observationRepository.save(observation(product, cnpj, price, observedAt, channel, outlier));
        auditRepository.save(PriceObservationAudit.builder()
                .observation(observation)
                .receiptId(receipt.getId())
                .householdId(household.getId())
                .contributedAt(observedAt)
                .build());
    }

    private static PriceObservation observation(Product product, String cnpj, String price, LocalDateTime observedAt,
                                                ReceiptChannel channel, boolean outlier) {
        return PriceObservation.builder()
                .product(product)
                .channel(channel)
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName("Loja")
                .unitPrice(new BigDecimal(price))
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .outlier(outlier)
                .build();
    }

    private Receipt receiptFor(Household household, String cnpj, ReceiptChannel channel, LocalDateTime issuedAt) {
        var user = userRepository.save(User.builder()
                .name("Tester").email("test" + UUID.randomUUID() + "@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(issuedAt)
                .build());
        var receipt = Receipt.builder()
                .user(user).household(household)
                .chaveAcesso("43" + System.nanoTime() + cnpj)
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName("Loja")
                .channel(channel)
                .issuedAt(issuedAt).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("Item")
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("10.00"))
                .build());
        return receipt;
    }
}
