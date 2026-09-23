package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
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

/**
 * Covers the category filter on {@code GET /receipts} after it became
 * multi-value (a {@code List<ProductCategory>} OR'd via {@code IN}). A single
 * category still narrows to one; multiple categories union; null = no filter.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReceiptListCategoryFilterIntegrationTest {

    @Autowired private ReceiptService receiptService;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MarketLocationRepository marketLocationRepository;

    private User user;

    @BeforeEach
    void seed() {
        var household = householdRepository.save(Household.builder().inviteCode(uniqueCode()).build());
        user = userRepository.save(User.builder()
                .name("Maria").email("maria-" + System.nanoTime() + "@e.test")
                .password("x").household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.now())
                .build());

        var arroz = productRepository.save(Product.builder()
                .normalizedName("arroz 5kg").category(ProductCategory.GROCERIES).build());
        var detergente = productRepository.save(Product.builder()
                .normalizedName("detergente 500ml").category(ProductCategory.CLEANING).build());
        var leite = productRepository.save(Product.builder()
                .normalizedName("leite 1l").category(ProductCategory.MEAT_DAIRY).build());

        // CNPJ ...5182 = SUPERMARKET (mercado); ...0111 = OTHER — for the scope filter test.
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006005182").cnpjRoot("93015006").segment(MerchantSegment.SUPERMARKET).build());
        marketLocationRepository.save(MarketLocation.builder()
                .cnpj("93015006000111").cnpjRoot("93015006").segment(MerchantSegment.OTHER).build());

        receipt(household, "93015006005182", LocalDateTime.of(2026, Month.APRIL, 10, 10, 0), arroz);       // GROCERIES
        receipt(household, "93015006000111", LocalDateTime.of(2026, Month.APRIL, 11, 10, 0), detergente);   // CLEANING
        receipt(household, "93015006005182", LocalDateTime.of(2026, Month.APRIL, 12, 10, 0), leite);        // MEAT_DAIRY
    }

    @Test
    void scopeSupported_keepsOnlyGroceryPharmacyMerchants() {
        var page = receiptService.list(user, null, null, null, null, null, null, MarketScope.SUPPORTED, PageRequest.of(0, 20));
        assertEquals(2, page.getTotalElements()); // as duas notas do CNPJ ...5182 (SUPERMARKET)
    }

    @Test
    void scopeOther_keepsOnlyNonGroceryMerchants() {
        var page = receiptService.list(user, null, null, null, null, null, null, MarketScope.OTHER, PageRequest.of(0, 20));
        assertEquals(1, page.getTotalElements()); // só a nota do CNPJ ...0111 (OTHER)
    }

    @Test
    void noCategory_returnsAll() {
        var page = receiptService.list(user, null, null, null, null, null, null, MarketScope.ALL, PageRequest.of(0, 20));
        assertEquals(3, page.getTotalElements());
    }

    @Test
    void singleCategory_narrowsToOne() {
        var page = receiptService.list(user, null, null, null,
                List.of(ProductCategory.GROCERIES), null, null, MarketScope.ALL, PageRequest.of(0, 20));
        assertEquals(1, page.getTotalElements());
    }

    @Test
    void multipleCategories_orThemTogether() {
        var page = receiptService.list(user, null, null, null,
                List.of(ProductCategory.GROCERIES, ProductCategory.CLEANING), null, null, MarketScope.ALL, PageRequest.of(0, 20));
        assertEquals(2, page.getTotalElements());
    }

    private void receipt(Household household, String cnpj, LocalDateTime issuedAt, Product product) {
        var receipt = Receipt.builder()
                .user(user).household(household).chaveAcesso(uniqueChave())
                .uf(UnidadeFederativa.RS).cnpjEmitente(cnpj).marketName("Mercado")
                .issuedAt(issuedAt).totalAmount(new BigDecimal("10.00")).qrPayload("test")
                .status(ReceiptStatus.CONFIRMED).confirmedAt(issuedAt)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .product(product).lineNumber(1).rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE).unit("UN")
                .unitPrice(new BigDecimal("10.00")).totalPrice(new BigDecimal("10.00"))
                .build());
        receiptRepository.save(receipt);
    }

    private static String uniqueCode() {
        return ("X" + UUID.randomUUID().toString().substring(0, 5)).toUpperCase();
    }

    private static String uniqueChave() {
        var digits = UUID.randomUUID().toString().replace("-", "").replaceAll("[^0-9]", "0");
        return (digits + "00000000000000000000000000000000000000000000").substring(0, 44);
    }
}
