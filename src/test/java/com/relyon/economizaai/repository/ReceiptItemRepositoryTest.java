package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the hand-written JPQL on {@link ReceiptItemRepository}: the unmatched
 * triage queue (filters + DESC NULLS LAST ordering), the EAN backfill bulk
 * update, product re-pointing on merge, and the confirmed-history feed.
 * The NULLS FIRST history ordering is covered by {@link ReceiptItemRepositoryOrderingTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptItemRepositoryTest {

    @Autowired private ReceiptItemRepository receiptItemRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private EntityManager entityManager;

    private User user;
    private UUID householdId;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("ITEM01").build());
        householdId = household.getId();
        user = userRepository.save(User.builder()
                .name("Tester").email("itemrepo@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Product savedProduct(String name) {
        return productRepository.save(Product.builder()
                .normalizedName(name).category(ProductCategory.GROCERIES).build());
    }

    private Receipt emptyReceipt(ReceiptStatus status, LocalDateTime issuedAt) {
        return Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado")
                .issuedAt(issuedAt).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(status)
                .build();
    }

    private ReceiptItem item(int lineNumber, String description, String ean, Product product, boolean excluded) {
        return ReceiptItem.builder()
                .lineNumber(lineNumber).rawDescription(description).ean(ean)
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("5.00")).totalPrice(new BigDecimal("5.00"))
                .product(product).excluded(excluded)
                .build();
    }

    // ---------------------------------------------------------- findUnmatchedForHousehold

    @Test
    void findUnmatchedForHousehold_filtersAndOrdersNewestFirstNullDatesLast() {
        var product = savedProduct("Leite");

        var olderReceipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.MAY, 10, 12, 0));
        olderReceipt.addItem(item(1, "OLDER UNMATCHED", null, null, false));
        olderReceipt.addItem(item(2, "ALREADY MATCHED", null, product, false));
        olderReceipt.addItem(item(3, "EXCLUDED UNMATCHED", null, null, true));
        receiptRepository.save(olderReceipt);

        var newerReceipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.MAY, 20, 12, 0));
        newerReceipt.addItem(item(1, "NEWER UNMATCHED", null, null, false));
        receiptRepository.save(newerReceipt);

        var pendingReceipt = emptyReceipt(ReceiptStatus.PENDING_CONFIRMATION, LocalDateTime.of(2026, Month.MAY, 25, 12, 0));
        pendingReceipt.addItem(item(1, "PENDING UNMATCHED", null, null, false));
        receiptRepository.save(pendingReceipt);

        var undatedReceipt = emptyReceipt(ReceiptStatus.CONFIRMED, null);
        undatedReceipt.addItem(item(1, "UNDATED UNMATCHED", null, null, false));
        receiptRepository.save(undatedReceipt);

        var unmatched = receiptItemRepository.findUnmatchedForHousehold(householdId);

        assertEquals(3, unmatched.size());
        assertEquals("NEWER UNMATCHED", unmatched.get(0).getRawDescription());
        assertEquals("OLDER UNMATCHED", unmatched.get(1).getRawDescription());
        assertEquals("UNDATED UNMATCHED", unmatched.get(2).getRawDescription(), "NULLS LAST on issuedAt");
    }

    // ---------------------------------------------------------- linkByEan

    @Test
    void linkByEan_linksOnlyUnmatchedNonExcludedItemsCaseInsensitively() {
        var product = savedProduct("Cerveja");
        var otherProduct = savedProduct("Refrigerante");

        var receipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.MAY, 1, 12, 0));
        receipt.addItem(item(1, "UPPER EAN", "ABC123", null, false));
        receipt.addItem(item(2, "LOWER EAN", "abc123", null, false));
        receipt.addItem(item(3, "EXCLUDED", "ABC123", null, true));
        receipt.addItem(item(4, "TAKEN", "ABC123", otherProduct, false));
        receipt.addItem(item(5, "OTHER EAN", "ZZZ999", null, false));
        receiptRepository.save(receipt);
        entityManager.flush();
        entityManager.clear();

        var linked = receiptItemRepository.linkByEan(product, "Abc123");
        entityManager.clear();

        assertEquals(2, linked, "both EAN casings link; excluded/taken/other-EAN stay put");
        var reloaded = receiptItemRepository.findAll();
        assertEquals(2, reloaded.stream()
                .filter(row -> row.getProduct() != null && product.getId().equals(row.getProduct().getId()))
                .count());
        var taken = reloaded.stream()
                .filter(row -> "TAKEN".equals(row.getRawDescription()))
                .findFirst().orElseThrow();
        assertEquals(otherProduct.getId(), taken.getProduct().getId(), "already-matched item keeps its product");
        var excluded = reloaded.stream()
                .filter(row -> "EXCLUDED".equals(row.getRawDescription()))
                .findFirst().orElseThrow();
        assertNull(excluded.getProduct());
    }

    // ---------------------------------------------------------- repointProduct

    @Test
    void repointProduct_movesEveryItemFromAbsorbedToSurvivor() {
        var survivor = savedProduct("Leite Integral");
        var absorbed = savedProduct("LEITE INT 1L");

        var receipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.MAY, 1, 12, 0));
        receipt.addItem(item(1, "DUPE A", null, absorbed, false));
        receipt.addItem(item(2, "DUPE B", null, absorbed, false));
        receipt.addItem(item(3, "ALREADY SURVIVOR", null, survivor, false));
        receiptRepository.save(receipt);
        entityManager.flush();
        entityManager.clear();

        var moved = receiptItemRepository.repointProduct(absorbed, survivor);
        entityManager.clear();

        assertEquals(2, moved);
        assertEquals(3, receiptItemRepository.countByProduct(survivor));
        assertEquals(0, receiptItemRepository.countByProduct(absorbed));
    }

    // ---------------------------------------------------------- findConfirmedHistoryForHousehold

    @Test
    void findConfirmedHistoryForHousehold_oldestFirstSkippingPendingExcludedAndUnmatched() {
        var product = savedProduct("Cafe");

        var februaryReceipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.FEBRUARY, 1, 12, 0));
        februaryReceipt.addItem(item(1, "FEBRUARY BUY", null, product, false));
        februaryReceipt.addItem(item(2, "EXCLUDED BUY", null, product, true));
        februaryReceipt.addItem(item(3, "UNMATCHED LINE", null, null, false));
        receiptRepository.save(februaryReceipt);

        var januaryReceipt = emptyReceipt(ReceiptStatus.CONFIRMED, LocalDateTime.of(2026, Month.JANUARY, 1, 12, 0));
        januaryReceipt.addItem(item(1, "JANUARY BUY", null, product, false));
        receiptRepository.save(januaryReceipt);

        var pendingReceipt = emptyReceipt(ReceiptStatus.PENDING_CONFIRMATION, LocalDateTime.of(2026, Month.MARCH, 1, 12, 0));
        pendingReceipt.addItem(item(1, "PENDING BUY", null, product, false));
        receiptRepository.save(pendingReceipt);

        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId);

        assertEquals(2, history.size());
        assertEquals("JANUARY BUY", history.get(0).getRawDescription(), "oldest first");
        assertEquals("FEBRUARY BUY", history.get(1).getRawDescription());
    }
}
