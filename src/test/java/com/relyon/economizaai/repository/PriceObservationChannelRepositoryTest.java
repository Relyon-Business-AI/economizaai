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

/**
 * Query-level proof that the two collaborative series NEVER cross-contaminate: physical
 * (IN_STORE) reads must exclude ONLINE observations and vice versa. This is enforced in the
 * JPQL {@code channel = '...'} predicates, which the mock-based service tests can't exercise.
 */
@DataJpaTest
@ActiveProfiles("test")
class PriceObservationChannelRepositoryTest {

    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private PriceObservationAuditRepository auditRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ReceiptRepository receiptRepository;

    private static final String CNPJ = "12345678000190";
    private static final LocalDateTime SINCE = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime OBSERVED = LocalDateTime.of(2026, 6, 1, 12, 0);

    private Product product;

    @BeforeEach
    void setUp() {
        product = productRepository.save(Product.builder().ean("789").normalizedName("Arroz").build());

        // Same product + same seller CNPJ, mixed channels across distinct households:
        //   IN_STORE: H1, H2, H3   (physical series)
        //   ONLINE:   H1, H4       (online series — H1 bought both channels)
        var h1 = household("H1"); var h2 = household("H2");
        var h3 = household("H3"); var h4 = household("H4");
        observe(product, CNPJ, ReceiptChannel.IN_STORE, new BigDecimal("10.00"), h1, false);
        observe(product, CNPJ, ReceiptChannel.IN_STORE, new BigDecimal("11.00"), h2, false);
        observe(product, CNPJ, ReceiptChannel.IN_STORE, new BigDecimal("12.00"), h3, false);
        observe(product, CNPJ, ReceiptChannel.ONLINE, new BigDecimal("20.00"), h1, false);
        observe(product, CNPJ, ReceiptChannel.ONLINE, new BigDecimal("22.00"), h4, false);
    }

    @Test
    void findRecentByProductAndMarket_returnsOnlyInStore() {
        var rows = observationRepository.findRecentByProductAndMarket(product.getId(), CNPJ, SINCE);
        assertThat(rows).hasSize(3)
                .allMatch(observation -> observation.getChannel() == ReceiptChannel.IN_STORE);
    }

    @Test
    void findRecentByProduct_returnsOnlyInStore() {
        var rows = observationRepository.findRecentByProduct(product.getId(), SINCE);
        assertThat(rows).hasSize(3)
                .allMatch(observation -> observation.getChannel() == ReceiptChannel.IN_STORE);
    }

    @Test
    void findRecentOnlineByProduct_returnsOnlyOnline() {
        var rows = observationRepository.findRecentOnlineByProduct(product.getId(), SINCE);
        assertThat(rows).hasSize(2)
                .allMatch(observation -> observation.getChannel() == ReceiptChannel.ONLINE);
    }

    @Test
    void findRecent_returnsOnlyInStore() {
        var rows = observationRepository.findRecent(SINCE);
        assertThat(rows).isNotEmpty()
                .allMatch(observation -> observation.getChannel() == ReceiptChannel.IN_STORE);
    }

    @Test
    void kAnonCount_physicalCountsOnlyInStoreHouseholds() {
        var count = auditRepository.countDistinctHouseholdsForProductMarket(product.getId(), CNPJ, SINCE);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void kAnonCount_onlineCountsOnlyOnlineHouseholds() {
        var count = auditRepository.countDistinctOnlineHouseholdsForProduct(product.getId(), SINCE);
        assertThat(count).isEqualTo(2); // H1 + H4 — H2/H3 (in-store only) excluded
    }

    @Test
    void kAnonPerMarket_countsOnlyInStoreHouseholds() {
        var rows = auditRepository.countDistinctHouseholdsForProductByMarket(product.getId(), SINCE);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCnpj()).isEqualTo(CNPJ);
        assertThat(rows.get(0).getHouseholds()).isEqualTo(3);
    }

    @Test
    void kAnonPerChain_poolsHouseholdsAcrossStoresOfTheSameRoot() {
        // A second store of the same chain (same 8-digit root) contributed by a 4th household:
        // per store it's a lone contributor, per chain the pool grows to 4.
        var siblingStore = "12345678000271";
        observe(product, siblingStore, ReceiptChannel.IN_STORE, new BigDecimal("10.50"), household("H6"), false);

        var rows = auditRepository.countDistinctHouseholdsForProductByChain(product.getId(), SINCE);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCnpjRoot()).isEqualTo("12345678");
        assertThat(rows.get(0).getHouseholds()).isEqualTo(4);
    }

    @Test
    void inStoreHouseholdCount_acrossAllMarketsCountsOnlyInStore() {
        assertThat(auditRepository.countDistinctInStoreHouseholdsForProduct(product.getId(), SINCE)).isEqualTo(3);
    }

    @Test
    void stateScopedReads_onlySeeObservationsSnapshottedInThoseStates() {
        var rsHousehold = household("H7");
        observeInState(product, "99887766000100", "RS", new BigDecimal("9.00"), rsHousehold);
        observeInState(product, "99887766000200", "SC", new BigDecimal("9.50"), household("H8"));

        var rsRows = observationRepository.findRecentByProductAndStates(product.getId(), List.of("RS"), SINCE);
        var rsHouseholds = auditRepository.countDistinctInStoreHouseholdsForProductInStates(
                product.getId(), List.of("RS"), SINCE);

        assertThat(rsRows).hasSize(1).allMatch(observation -> "RS".equals(observation.getState()));
        assertThat(rsHouseholds).isEqualTo(1);
    }

    @Test
    void confirmedUfsForHousehold_listsTheStatesTheHouseholdScannedIn() {
        var household = household("H9");
        receiptRepository.save(receiptFor(household, CNPJ, ReceiptChannel.IN_STORE));

        var ufs = receiptRepository.findConfirmedUfsForHousehold(household.getId());

        assertThat(ufs).containsExactly(UnidadeFederativa.RS);
    }

    @Test
    void outlierObservationsExcludedFromBothSeries() {
        var outlierHousehold = household("H5");
        observe(product, CNPJ, ReceiptChannel.IN_STORE, new BigDecimal("999.00"), outlierHousehold, true);
        observe(product, CNPJ, ReceiptChannel.ONLINE, new BigDecimal("999.00"), outlierHousehold, true);

        assertThat(observationRepository.findRecentByProductAndMarket(product.getId(), CNPJ, SINCE)).hasSize(3);
        assertThat(observationRepository.findRecentOnlineByProduct(product.getId(), SINCE)).hasSize(2);
    }

    private Household household(String code) {
        return householdRepository.save(Household.builder().inviteCode(code).build());
    }

    /** Persist one observation + its audit row (audit needs a real receipt for the FK). */
    private void observe(Product product, String cnpj, ReceiptChannel channel,
                         BigDecimal price, Household household, boolean outlier) {
        var receipt = receiptRepository.save(receiptFor(household, cnpj, channel));
        var observation = observationRepository.save(PriceObservation.builder()
                .product(product)
                .channel(channel)
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName("Loja")
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(OBSERVED)
                .outlier(outlier)
                .build());
        auditRepository.save(PriceObservationAudit.builder()
                .observation(observation)
                .receiptId(receipt.getId())
                .householdId(household.getId())
                .contributedAt(OBSERVED)
                .build());
    }

    /** Persist an IN_STORE observation whose market location was snapshotted in {@code state}. */
    private void observeInState(Product product, String cnpj, String state, BigDecimal price, Household household) {
        var receipt = receiptRepository.save(receiptFor(household, cnpj, ReceiptChannel.IN_STORE));
        var observation = observationRepository.save(PriceObservation.builder()
                .product(product)
                .channel(ReceiptChannel.IN_STORE)
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName("Loja")
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(OBSERVED)
                .state(state)
                .build());
        auditRepository.save(PriceObservationAudit.builder()
                .observation(observation)
                .receiptId(receipt.getId())
                .householdId(household.getId())
                .contributedAt(OBSERVED)
                .build());
    }

    private Receipt receiptFor(Household household, String cnpj, ReceiptChannel channel) {
        var user = userRepository.save(User.builder()
                .name("Tester").email("u" + UUID.randomUUID() + "@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(OBSERVED)
                .build());
        var receipt = Receipt.builder()
                .user(user).household(household)
                .chaveAcesso("43" + System.nanoTime() + cnpj)
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName("Loja")
                .channel(channel)
                .issuedAt(OBSERVED).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("Arroz")
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("10.00"))
                .build());
        return receipt;
    }
}
