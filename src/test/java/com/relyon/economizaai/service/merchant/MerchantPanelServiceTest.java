package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.MerchantAccess;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductStateHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers the merchant mini-panel, especially the k-anonymity contract
 * (CLAUDE.md: every endpoint exposing aggregates must assert the K-gate):
 * a (product, state) row is emitted ONLY when both the chain side and the
 * region side pass minHouseholdsForPublic AND minObservationsPerProductMarket.
 */
@ExtendWith(MockitoExtension.class)
class MerchantPanelServiceTest {

    private static final String CHAIN_ROOT = "93015006";
    private static final String STATE = "RS";

    @Mock private MerchantAccessRepository merchantAccessRepository;
    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;

    private Product product;
    private User merchantUser;
    private MerchantPanelService service;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        service = new MerchantPanelService(merchantAccessRepository, marketLocationRepository,
                receiptRepository, observationRepository, auditRepository, properties);
        product = Product.builder().id(UUID.randomUUID()).normalizedName("Leite Integral 1L").build();
        merchantUser = User.builder().id(UUID.randomUUID()).name("Mercado Teste")
                .email("merchant@economizaai.app").role(Role.MERCHANT).build();
        lenient().when(merchantAccessRepository.findAllByUserId(merchantUser.getId()))
                .thenReturn(List.of(MerchantAccess.builder().user(merchantUser).cnpjRoot(CHAIN_ROOT).build()));
    }

    private PriceObservation observation(String price) {
        return PriceObservation.builder()
                .product(product).state(STATE)
                .marketCnpjRoot(CHAIN_ROOT)
                .unitPrice(new BigDecimal(price))
                .quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now())
                .build();
    }

    private List<PriceObservation> observations(String... prices) {
        return Stream.of(prices).map(this::observation).toList();
    }

    private static ProductStateHouseholdCount householdCount(UUID productId, String state, long households) {
        return new ProductStateHouseholdCount() {
            @Override public UUID getProductId() { return productId; }
            @Override public String getState() { return state; }
            @Override public long getHouseholds() { return households; }
        };
    }

    private void stubChain(List<PriceObservation> chainObservations, long chainHouseholds) {
        when(observationRepository.findRecentByMarketCnpjRoot(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(chainObservations);
        lenient().when(auditRepository.countDistinctHouseholdsPerProductStateForChain(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(List.of(householdCount(product.getId(), STATE, chainHouseholds)));
    }

    private void stubRegion(List<PriceObservation> regionObservations, long regionHouseholds) {
        lenient().when(observationRepository.findRecentByProductIdsAndStates(anyList(), anyList(), any(LocalDateTime.class)))
                .thenReturn(regionObservations);
        lenient().when(auditRepository.countDistinctHouseholdsPerProductState(anyList(), anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(householdCount(product.getId(), STATE, regionHouseholds)));
    }

    @Test
    void priceComparison_bothSidesPassGates_emitsRowWithMediansAndDelta() {
        stubChain(observations("4.00", "5.00", "6.00"), 3);
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 5);

        var response = service.priceComparison(merchantUser);

        assertThat(response.products()).hasSize(1);
        var row = response.products().get(0);
        assertThat(row.productName()).isEqualTo("Leite Integral 1L");
        assertThat(row.state()).isEqualTo(STATE);
        assertThat(row.chainMedianPrice()).isEqualByComparingTo("5.00");
        assertThat(row.regionMedianPrice()).isEqualByComparingTo("4.00");
        assertThat(row.deltaPercent()).isEqualByComparingTo("25.0");
        assertThat(row.chainSampleCount()).isEqualTo(3);
        assertThat(row.regionSampleCount()).isEqualTo(4);
    }

    @Test
    void priceComparison_chainSideBelowKAnonymity_dropsRow() {
        stubChain(observations("4.00", "5.00", "6.00"), 2);
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 5);

        assertThat(service.priceComparison(merchantUser).products()).isEmpty();
    }

    @Test
    void priceComparison_regionSideBelowKAnonymity_dropsRow() {
        stubChain(observations("4.00", "5.00", "6.00"), 3);
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 2);

        assertThat(service.priceComparison(merchantUser).products()).isEmpty();
    }

    @Test
    void priceComparison_chainSideBelowSampleFloor_dropsRow() {
        stubChain(observations("4.00", "5.00"), 3);
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 5);

        assertThat(service.priceComparison(merchantUser).products()).isEmpty();
    }

    @Test
    void priceComparison_regionSideBelowSampleFloor_dropsRow() {
        stubChain(observations("4.00", "5.00", "6.00"), 3);
        stubRegion(observations("4.00", "4.00"), 5);

        assertThat(service.priceComparison(merchantUser).products()).isEmpty();
    }

    @Test
    void priceComparison_noGrants_returnsEmpty() {
        when(merchantAccessRepository.findAllByUserId(merchantUser.getId())).thenReturn(List.of());

        assertThat(service.priceComparison(merchantUser).products()).isEmpty();
    }

    @Test
    void profile_listsChainStoresRankedByReceiptCount() {
        var quietStore = MarketLocation.builder().cnpj("93015006000101").cnpjRoot(CHAIN_ROOT)
                .name("Loja Centro").city("Porto Alegre").state(STATE)
                .segment(MerchantSegment.SUPERMARKET).build();
        var busyStore = MarketLocation.builder().cnpj("93015006000202").cnpjRoot(CHAIN_ROOT)
                .name("Loja Zona Sul").city("Porto Alegre").state(STATE)
                .segment(MerchantSegment.SUPERMARKET).build();
        when(marketLocationRepository.findAllByCnpjRoot(CHAIN_ROOT)).thenReturn(List.of(quietStore, busyStore));
        when(receiptRepository.countByCnpjEmitente("93015006000101")).thenReturn(2L);
        when(receiptRepository.countByCnpjEmitente("93015006000202")).thenReturn(9L);

        var response = service.profile(merchantUser);

        assertThat(response.chains()).hasSize(1);
        var chain = response.chains().get(0);
        assertThat(chain.cnpjRoot()).isEqualTo(CHAIN_ROOT);
        assertThat(chain.stores()).hasSize(2);
        assertThat(chain.stores().get(0).cnpj()).isEqualTo("93015006000202");
        assertThat(chain.stores().get(0).receiptCount()).isEqualTo(9L);
    }
}
