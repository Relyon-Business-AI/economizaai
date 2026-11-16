package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ContributedPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The two reads behind the household savings: the household's own lines and the community prices. */
@DataJpaTest
@ActiveProfiles("test")
class HouseholdSavingsRepositoryTest {

    @Autowired private ReceiptItemRepository receiptItemRepository;
    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private PriceObservationAuditRepository auditRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;

    private static final String CNPJ = "12345678000190";
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 31, 23, 59);
    private static final LocalDateTime INSIDE_WINDOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    private Product rice;
    private Household household;

    @BeforeEach
    void setUp() {
        rice = productRepository.save(Product.builder().ean("789").normalizedName("Arroz").build());
        household = household("H1");
    }

    @Test
    void ownLines_areTheNonExcludedLinesOfConfirmedInStoreReceiptsInTheWindow() {
        saveReceipt(household, ReceiptStatus.CONFIRMED, ReceiptChannel.IN_STORE, INSIDE_WINDOW, "counted", false);
        saveReceipt(household, ReceiptStatus.CONFIRMED, ReceiptChannel.IN_STORE, INSIDE_WINDOW, "excluded", true);
        saveReceipt(household, ReceiptStatus.PENDING_CONFIRMATION, ReceiptChannel.IN_STORE, INSIDE_WINDOW, "pending", false);
        saveReceipt(household, ReceiptStatus.CONFIRMED, ReceiptChannel.ONLINE, INSIDE_WINDOW, "online", false);
        saveReceipt(household, ReceiptStatus.CONFIRMED, ReceiptChannel.IN_STORE, WINDOW_START.minusDays(1), "too old", false);
        saveReceipt(household("H2"), ReceiptStatus.CONFIRMED, ReceiptChannel.IN_STORE, INSIDE_WINDOW, "someone else", false);

        var lines = receiptItemRepository.findConfirmedInStoreLinesForHousehold(household.getId(), WINDOW_START, WINDOW_END);

        assertThat(lines).extracting(ReceiptItem::getRawDescription).containsExactly("counted");
        assertThat(lines.get(0).getProduct().getId()).isEqualTo(rice.getId());
    }

    @Test
    void contributedPrices_carryTheContributingHouseholdAndSkipOutliersOnlineAndOtherDays() {
        var neighbour = household("H3");
        observe(neighbour, ReceiptChannel.IN_STORE, "10.00", INSIDE_WINDOW, false);
        observe(neighbour, ReceiptChannel.IN_STORE, "99.00", INSIDE_WINDOW, true);
        observe(neighbour, ReceiptChannel.ONLINE, "8.00", INSIDE_WINDOW, false);
        observe(neighbour, ReceiptChannel.IN_STORE, "11.00", WINDOW_END.plusDays(5), false);

        var prices = auditRepository.findContributedPrices(List.of(rice.getId()), WINDOW_START, WINDOW_END);

        assertThat(prices).hasSize(1);
        ContributedPrice price = prices.get(0);
        assertThat(price.getProductId()).isEqualTo(rice.getId());
        assertThat(price.getHouseholdId()).isEqualTo(neighbour.getId());
        assertThat(price.getMarketCnpj()).isEqualTo(CNPJ);
        assertThat(price.getState()).isEqualTo("RS");
        assertThat(price.getUnitPrice()).isEqualByComparingTo("10.00");
    }

    private Household household(String inviteCode) {
        return householdRepository.save(Household.builder().inviteCode(inviteCode).build());
    }

    private Receipt saveReceipt(Household owner, ReceiptStatus status, ReceiptChannel channel,
                                LocalDateTime issuedAt, String description, boolean excluded) {
        var user = userRepository.save(User.builder()
                .name("Tester").email("test" + UUID.randomUUID() + "@economizaai.app").password("x")
                .household(owner)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0").acceptedLegalAt(issuedAt)
                .build());
        var receipt = Receipt.builder()
                .user(user).household(owner)
                .chaveAcesso("43" + System.nanoTime() + CNPJ)
                .uf(UnidadeFederativa.RS).cnpjEmitente(CNPJ).marketName("Loja")
                .channel(channel).issuedAt(issuedAt).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(status)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription(description).product(rice).excluded(excluded)
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("10.00"))
                .build());
        return receiptRepository.save(receipt);
    }

    private void observe(Household contributor, ReceiptChannel channel, String unitPrice,
                         LocalDateTime observedAt, boolean outlier) {
        var receipt = saveReceipt(contributor, ReceiptStatus.CONFIRMED, channel, observedAt, "contribution", false);
        var observation = observationRepository.save(PriceObservation.builder()
                .product(rice).channel(channel)
                .marketCnpj(CNPJ).marketCnpjRoot(CNPJ.substring(0, 8)).marketName("Loja")
                .unitPrice(new BigDecimal(unitPrice)).quantity(BigDecimal.ONE)
                .observedAt(observedAt).outlier(outlier).state("RS")
                .build());
        auditRepository.save(PriceObservationAudit.builder()
                .observation(observation).receiptId(receipt.getId())
                .householdId(contributor.getId()).contributedAt(observedAt)
                .build());
    }
}
