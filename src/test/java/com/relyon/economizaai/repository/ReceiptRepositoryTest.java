package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hand-written JPQL on {@link ReceiptRepository}: the confirmed-spend
 * window sum (status + date filters, COALESCE-to-zero), the distinct-CNPJ
 * projection, and the fetch-join detail load. The hour histogram is covered by
 * {@link ReceiptHourHistogramTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptRepositoryTest {

    private static final String CNPJ_A = "12345678000190";
    private static final String CNPJ_B = "98765432000111";

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;

    private User user;
    private UUID householdId;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("RCPT01").build());
        householdId = household.getId();
        user = userRepository.save(User.builder()
                .name("Tester").email("rcptrepo@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Receipt saveReceipt(User owner, String cnpj, ReceiptStatus status,
                                LocalDateTime confirmedAt, BigDecimal total) {
        return receiptRepository.save(Receipt.builder()
                .user(owner).household(owner.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(total).confirmedAt(confirmedAt)
                .qrPayload("payload").status(status)
                .build());
    }

    private User otherHouseholdUser() {
        var household = householdRepository.save(Household.builder().inviteCode("RCPT02").build());
        return userRepository.save(User.builder()
                .name("Neighbor").email("neighbor@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    // ---------------------------------------------------------- sumConfirmedTotalSince

    @Test
    void sumConfirmedTotalSince_sumsOnlyConfirmedInWindowForHousehold() {
        var since = LocalDateTime.of(2026, Month.APRIL, 1, 0, 0);
        // In window, confirmed — counted.
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED,
                LocalDateTime.of(2026, Month.MAY, 1, 10, 0), new BigDecimal("100.00"));
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED,
                LocalDateTime.of(2026, Month.APRIL, 1, 0, 0), new BigDecimal("25.50"));
        // Confirmed before the window — out.
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED,
                LocalDateTime.of(2026, Month.JANUARY, 10, 10, 0), new BigDecimal("50.00"));
        // Pending in window — out.
        saveReceipt(user, CNPJ_A, ReceiptStatus.PENDING_CONFIRMATION,
                LocalDateTime.of(2026, Month.MAY, 2, 10, 0), new BigDecimal("999.00"));
        // Another household — out.
        saveReceipt(otherHouseholdUser(), CNPJ_A, ReceiptStatus.CONFIRMED,
                LocalDateTime.of(2026, Month.MAY, 3, 10, 0), new BigDecimal("70.00"));

        var sum = receiptRepository.sumConfirmedTotalSince(householdId, since);

        // `since` is inclusive (>=): 100.00 + 25.50.
        assertEquals(0, sum.compareTo(new BigDecimal("125.50")));
    }

    @Test
    void sumConfirmedTotalSince_returnsZeroNotNullWhenNothingMatches() {
        var sum = receiptRepository.sumConfirmedTotalSince(householdId,
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0));

        assertNotNull(sum, "COALESCE keeps the contract null-safe");
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));
    }

    // ---------------------------------------------------------- findDistinctCnpjsByHousehold

    @Test
    void findDistinctCnpjsByHousehold_confirmedNonNullDeduped() {
        var confirmedAt = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("10.00"));
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("20.00"));
        saveReceipt(user, CNPJ_B, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("30.00"));
        // Pending and null-CNPJ receipts don't contribute.
        saveReceipt(user, "55666777000188", ReceiptStatus.PENDING_CONFIRMATION, null, new BigDecimal("40.00"));
        saveReceipt(user, null, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("50.00"));
        // Another household's market doesn't leak in.
        saveReceipt(otherHouseholdUser(), "11222333000144", ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("60.00"));

        var cnpjs = receiptRepository.findDistinctCnpjsByHousehold(householdId);

        assertEquals(Set.of(CNPJ_A, CNPJ_B), Set.copyOf(cnpjs));
    }

    // ---------------------------------------------------------- internal-account filter

    private int inviteSeq = 0;

    private User internalUser(String email, Role role, boolean excludedFromMetrics) {
        var household = householdRepository.save(Household.builder().inviteCode("INT" + (++inviteSeq)).build());
        return userRepository.save(User.builder()
                .name("Internal").email(email).password("x")
                .role(role).excludedFromMetrics(excludedFromMetrics)
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    @Test
    void internalFilter_excludesAdminTestAndFlaggedAccountsByDefault() {
        var confirmedAt = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
        // Real user (the setUp `user`) — always counted.
        saveReceipt(user, CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("100.00"));
        // Admin, test-domain, and metrics-excluded submitters — internal.
        saveReceipt(internalUser("boss@test.com", Role.ADMIN, false), CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("10.00"));
        saveReceipt(internalUser("qa@economizaai.app", Role.USER, false), CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("20.00"));
        saveReceipt(internalUser("robo@cloudtestlabaccounts.com", Role.USER, false), CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("30.00"));
        saveReceipt(internalUser("flagged@real.com", Role.USER, true), CNPJ_A, ReceiptStatus.CONFIRMED, confirmedAt, new BigDecimal("40.00"));

        // Off by default: only the one real user's receipt / household / spend.
        assertEquals(1L, receiptRepository.countReceipts(false));
        assertEquals(1L, receiptRepository.countActiveHouseholdsSince(
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0), false));
        assertEquals(0, receiptRepository.sumConfirmedTotal(false).compareTo(new BigDecimal("100.00")));

        // Toggle on: everything is included.
        assertEquals(5L, receiptRepository.countReceipts(true));
        assertEquals(5L, receiptRepository.countActiveHouseholdsSince(
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0), true));
        assertEquals(0, receiptRepository.sumConfirmedTotal(true).compareTo(new BigDecimal("200.00")));
    }

    // ---------------------------------------------------------- findByIdWithItemsAndProducts

    @Test
    void findByIdWithItemsAndProducts_loadsItemsAndTheirProducts() {
        var product = productRepository.save(Product.builder()
                .ean("789").normalizedName("Arroz").category(ProductCategory.GROCERIES).build());
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente(CNPJ_A).marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("15.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("ARROZ 5KG")
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("10.00"))
                .product(product).build());
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(2).rawDescription("ITEM SEM PRODUTO")
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("5.00"))
                .build());
        var savedId = receiptRepository.save(receipt).getId();

        var loaded = receiptRepository.findByIdWithItemsAndProducts(savedId).orElseThrow();

        assertEquals(2, loaded.getItems().size());
        var productNames = loaded.getItems().stream()
                .map(item -> item.getProduct() != null ? item.getProduct().getNormalizedName() : null)
                .collect(Collectors.toSet());
        assertTrue(productNames.contains("Arroz"), "fetch-joined product is populated");
        assertTrue(receiptRepository.findByIdWithItemsAndProducts(UUID.randomUUID()).isEmpty());
    }
}
