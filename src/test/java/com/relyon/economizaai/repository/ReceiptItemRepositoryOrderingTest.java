package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a receipt with a NULL {@code issuedAt} must NOT be treated as the
 * most-recent purchase. The history queries order {@code issuedAt ASC NULLS FIRST}
 * so genuinely-dated receipts come last (last-wins), keeping last-paid-price correct.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptItemRepositoryOrderingTest {

    @Autowired private ReceiptItemRepository receiptItemRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;

    private UUID householdId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("ORD001").build());
        householdId = household.getId();
        var user = userRepository.save(User.builder()
                .name("Tester").email("ord@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
        var product = productRepository.save(Product.builder()
                .ean("999").normalizedName("Leite").category(ProductCategory.GROCERIES).build());
        productId = product.getId();

        // Null-dated receipt with a HIGH price — Postgres would sort NULLS LAST by
        // default, wrongly making this the "most recent" (last-wins) purchase.
        receiptRepository.save(buildReceipt(user, null, new BigDecimal("99.99")));
        // Genuinely-dated receipt with the real most-recent price.
        receiptRepository.save(buildReceipt(user, LocalDateTime.of(2026, Month.MAY, 1, 12, 0), new BigDecimal("4.50")));
    }

    private Receipt buildReceipt(User user, LocalDateTime issuedAt, BigDecimal unitPrice) {
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(issuedAt).totalAmount(unitPrice)
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        var product = productRepository.findById(productId).orElseThrow();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("Leite")
                .quantity(BigDecimal.ONE).unitPrice(unitPrice).totalPrice(unitPrice)
                .product(product).build());
        return receipt;
    }

    @Test
    void findHouseholdHistoryForProduct_nullIssuedAtSortsFirstNotLast() {
        var history = receiptItemRepository.findHouseholdHistoryForProduct(productId, householdId);

        assertEquals(2, history.size());
        // NULLS FIRST: the null-dated receipt is first (oldest)...
        assertTrue(new BigDecimal("99.99").compareTo(history.get(0).getUnitPrice()) == 0);
        // ...so the genuinely-dated receipt wins as the last (most-recent) entry.
        assertTrue(new BigDecimal("4.50").compareTo(history.get(history.size() - 1).getUnitPrice()) == 0);
    }
}
