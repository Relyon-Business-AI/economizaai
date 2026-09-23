package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class InsightsRepositoryTest {

    @Autowired private InsightsRepository insightsRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private MarketLocationRepository marketLocationRepository;

    // Market A (CNPJ ...190) = SUPERMARKET (index-supported); Market B (...111) = OTHER.
    private static final String CNPJ_SUPERMARKET = "12345678000190";
    private static final String CNPJ_OTHER = "98765432000111";

    // ALL scope binds a sentinel CNPJ list (never empty) — the branch is unused.
    private static final String ALL = "ALL";
    private static final String SUPPORTED = "SUPPORTED";
    private static final String OTHER = "OTHER";
    private static final List<String> SENTINEL = List.of("__none__");

    private Household household;
    private User user;
    private Product groceries;

    @BeforeEach
    void setUp() {
        household = householdRepository.save(Household.builder().inviteCode("TEST01").build());
        user = userRepository.save(User.builder()
                .name("Tester").email("test@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());

        marketLocationRepository.save(MarketLocation.builder()
                .cnpj(CNPJ_SUPERMARKET).cnpjRoot("12345678").segment(MerchantSegment.SUPERMARKET).build());
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj(CNPJ_OTHER).cnpjRoot("98765432").segment(MerchantSegment.OTHER).build());

        groceries = productRepository.save(Product.builder()
                .ean("789").normalizedName("Arroz").category(ProductCategory.GROCERIES).build());
        var produce = productRepository.save(Product.builder()
                .ean("888").normalizedName("Banana").category(ProductCategory.PRODUCE).build());

        receiptRepository.save(buildReceipt(user, CNPJ_SUPERMARKET, "Mercado A",
                LocalDateTime.of(2026, Month.MARCH, 10, 18, 0), new BigDecimal("100.00"),
                ReceiptStatus.CONFIRMED, groceries, produce));
        receiptRepository.save(buildReceipt(user, CNPJ_SUPERMARKET, "Mercado A",
                LocalDateTime.of(2026, Month.APRIL, 5, 18, 0), new BigDecimal("80.00"),
                ReceiptStatus.CONFIRMED, groceries, null));
        receiptRepository.save(buildReceipt(user, CNPJ_OTHER, "Mercado B",
                LocalDateTime.of(2026, Month.APRIL, 20, 18, 0), new BigDecimal("50.00"),
                ReceiptStatus.CONFIRMED, produce, null));
        // pending — should not appear in aggregates
        receiptRepository.save(buildReceipt(user, CNPJ_SUPERMARKET, "Mercado A",
                LocalDateTime.of(2026, Month.APRIL, 22, 18, 0), new BigDecimal("999.00"),
                ReceiptStatus.PENDING_CONFIRMATION, groceries, null));
    }

    private Receipt buildReceipt(User user, String cnpj, String marketName, LocalDateTime issuedAt,
                                  BigDecimal total, ReceiptStatus status, Product first, Product second) {
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime() + (cnpj != null ? cnpj.substring(0, 14) : "00000000000000"))
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName(marketName)
                .issuedAt(issuedAt).totalAmount(total)
                .qrPayload("payload").status(status)
                .build();
        // Split the total across items so item-totals sum back to the receipt total —
        // insights now derive sums from non-excluded items rather than the receipt header.
        var firstShare = second != null ? total.divide(new BigDecimal("2")) : total;
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription(first.getNormalizedName())
                .quantity(BigDecimal.ONE).totalPrice(firstShare)
                .product(first).build());
        if (second != null) {
            receipt.addItem(ReceiptItem.builder()
                    .lineNumber(2).rawDescription(second.getNormalizedName())
                    .quantity(BigDecimal.ONE).totalPrice(total.divide(new BigDecimal("2")))
                    .product(second).build());
        }
        return receipt;
    }

    private static final LocalDateTime ALL_TIME_FROM = LocalDateTime.of(1900, Month.JANUARY, 1, 0, 0);
    private static final LocalDateTime ALL_TIME_TO = LocalDateTime.of(2999, Month.DECEMBER, 31, 23, 59);

    private List<String> supported() {
        return insightsRepository.supportedCnpjs(household.getId(), MerchantSupportGate.supportedSegments());
    }

    // ---------- legacy (ALL scope) behaviour is unchanged ----------

    @Test
    void totalSpend_excludesPendingAndRespectsRange() {
        var total = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, ALL, SENTINEL);
        assertEquals(0, total.compareTo(new BigDecimal("230.00")));

        var aprilOnly = insightsRepository.totalSpend(household.getId(),
                LocalDateTime.of(2026, Month.APRIL, 1, 0, 0),
                LocalDateTime.of(2026, Month.APRIL, 30, 23, 59), ALL, SENTINEL);
        assertEquals(0, aprilOnly.compareTo(new BigDecimal("130.00")));
    }

    @Test
    void spendByMonth_returnsBucketsInOrder() {
        var rows = insightsRepository.spendByMonth(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, ALL, SENTINEL);
        assertEquals(2, rows.size());
        assertEquals(3, ((Number) rows.get(0)[1]).intValue());
        assertEquals(4, ((Number) rows.get(1)[1]).intValue());
    }

    @Test
    void spendByMarket_groupsByCnpj() {
        var rows = insightsRepository.spendByMarket(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, ALL, SENTINEL);
        assertEquals(2, rows.size());
        assertEquals(CNPJ_SUPERMARKET, rows.get(0)[0]);
        assertEquals(0, ((BigDecimal) rows.get(0)[2]).compareTo(new BigDecimal("180.00")));
    }

    @Test
    void spendByCategory_groupsByProductCategory() {
        var rows = insightsRepository.spendByCategory(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, ALL, SENTINEL);
        assertTrue(rows.size() >= 2);
    }

    // ---------- scope: SUPPORTED vs OTHER ----------

    @Test
    void supportedCnpjs_returnsOnlyGroceryPharmacy() {
        var supported = supported();
        assertEquals(1, supported.size());
        assertEquals(CNPJ_SUPERMARKET, supported.get(0));
    }

    @Test
    void totalSpend_supportedScope_countsOnlyGroceryPharmacy() {
        var total = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, SUPPORTED, supported());
        assertEquals(0, total.compareTo(new BigDecimal("180.00"))); // Mercado A only (100 + 80)
    }

    @Test
    void totalSpend_otherScope_countsOnlyNonGrocery() {
        var total = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, OTHER, supported());
        assertEquals(0, total.compareTo(new BigDecimal("50.00"))); // Mercado B only
    }

    @Test
    void totalSpend_allScope_equalsSupportedPlusOther() {
        var all = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, ALL, SENTINEL);
        var supportedTotal = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, SUPPORTED, supported());
        var otherTotal = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, OTHER, supported());
        assertEquals(0, all.compareTo(supportedTotal.add(otherTotal)));
    }

    @Test
    void spendByMarket_supportedScope_returnsOnlyGroceryMarket() {
        var rows = insightsRepository.spendByMarket(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, SUPPORTED, supported());
        assertEquals(1, rows.size());
        assertEquals(CNPJ_SUPERMARKET, rows.get(0)[0]);
    }

    @Test
    void spendByMarket_otherScope_returnsOnlyNonGroceryMarket() {
        var rows = insightsRepository.spendByMarket(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, OTHER, supported());
        assertEquals(1, rows.size());
        assertEquals(CNPJ_OTHER, rows.get(0)[0]);
    }

    // ---------- edge cases ----------

    @Test
    void supportedScope_withEmptySupportedSet_returnsNothing() {
        // Sentinel list stands in for "no grocery/pharmacy CNPJs yet": SUPPORTED → 0.
        var total = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, SUPPORTED, SENTINEL);
        assertEquals(0, total.compareTo(BigDecimal.ZERO));
    }

    @Test
    void otherScope_withEmptySupportedSet_returnsEverything() {
        // With no supported CNPJs, OTHER covers all confirmed spend.
        var total = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, OTHER, SENTINEL);
        assertEquals(0, total.compareTo(new BigDecimal("230.00")));
    }

    @Test
    void otherScope_includesReceiptsWithNoCnpj() {
        // A photo/manual receipt has no CNPJ — it must land in OTHER, never SUPPORTED.
        receiptRepository.save(buildReceipt(user, null, "Foto sem CNPJ",
                LocalDateTime.of(2026, Month.MAY, 1, 12, 0), new BigDecimal("30.00"),
                ReceiptStatus.CONFIRMED, groceries, null));

        var other = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, OTHER, supported());
        assertEquals(0, other.compareTo(new BigDecimal("80.00"))); // Mercado B (50) + null-CNPJ (30)

        var supportedTotal = insightsRepository.totalSpend(household.getId(), ALL_TIME_FROM, ALL_TIME_TO, SUPPORTED, supported());
        assertEquals(0, supportedTotal.compareTo(new BigDecimal("180.00"))); // unchanged — null CNPJ excluded
    }
}
