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
import com.relyon.economizaai.repository.PriceObservationAuditRepository.BrandDemandCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReceiptRepository.ReceiptActivityRow;
import com.relyon.economizaai.service.CategoryResolver;
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
    @Mock private CategoryResolver categoryResolver;

    private Product product;
    private User merchantUser;
    private MerchantPanelService service;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        service = new MerchantPanelService(merchantAccessRepository, marketLocationRepository,
                receiptRepository, observationRepository, auditRepository, categoryResolver, properties);
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

    // --- category benchmark ---

    @Test
    void categoryBenchmark_rollsUpOnlyKPassingRows() {
        var groupId = UUID.randomUUID();
        stubChain(observations("4.00", "5.00", "6.00"), 3);
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 5);
        lenient().when(categoryResolver.groupIdOf(any())).thenReturn(groupId);
        lenient().when(categoryResolver.slugForId(groupId)).thenReturn("mercearia");
        lenient().when(categoryResolver.labelFor(eq(groupId), any())).thenReturn("Mercearia");

        var response = service.categoryBenchmark(merchantUser, null);

        assertThat(response.categories()).hasSize(1);
        var row = response.categories().get(0);
        assertThat(row.categorySlug()).isEqualTo("mercearia");
        assertThat(row.state()).isEqualTo(STATE);
        assertThat(row.chainMedianPrice()).isEqualByComparingTo("5.00");
        assertThat(row.regionMedianPrice()).isEqualByComparingTo("4.00");
        assertThat(row.deltaPercent()).isEqualByComparingTo("25.0");
        assertThat(row.productCount()).isEqualTo(1);
    }

    @Test
    void categoryBenchmark_subKProductDoesNotContribute() {
        stubChain(observations("4.00", "5.00", "6.00"), 2); // chain below K
        stubRegion(observations("4.00", "4.00", "4.00", "8.00"), 5);

        assertThat(service.categoryBenchmark(merchantUser, null).categories()).isEmpty();
    }

    // --- brand share ---

    private static BrandDemandCount brandDemand(String brand, long observations, long households) {
        return new BrandDemandCount() {
            @Override public String getBrand() { return brand; }
            @Override public long getObservations() { return observations; }
            @Override public long getHouseholds() { return households; }
        };
    }

    @Test
    void brandShare_computesSharesOverKPassingBrands() {
        when(observationRepository.findRecentByMarketCnpjRoot(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(observations("4.00"));
        when(auditRepository.brandDemandInStates(eq(List.of(STATE)), any(LocalDateTime.class), eq(3L)))
                .thenReturn(List.of(brandDemand("Tirol", 30, 4), brandDemand("Italac", 10, 3)));

        var response = service.brandShare(merchantUser, null, null);

        assertThat(response.states()).containsExactly(STATE);
        assertThat(response.brands()).hasSize(2);
        assertThat(response.brands().get(0).brand()).isEqualTo("Tirol");
        assertThat(response.brands().get(0).sharePercent()).isEqualByComparingTo("75.0");
        assertThat(response.brands().get(0).households()).isEqualTo(4);
        assertThat(response.brands().get(1).sharePercent()).isEqualByComparingTo("25.0");
    }

    @Test
    void brandShare_passesFullKToRepositoryGate() {
        when(observationRepository.findRecentByMarketCnpjRoot(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(observations("4.00"));
        when(auditRepository.brandDemandInStates(eq(List.of(STATE)), any(LocalDateTime.class), eq(3L)))
                .thenReturn(List.of());

        var response = service.brandShare(merchantUser, null, null);

        // K=3 (minHouseholdsForPublic) is handed to the DB HAVING gate, never the relaxed grocery n=1.
        assertThat(response.brands()).isEmpty();
    }

    @Test
    void brandShare_noRegion_returnsEmpty() {
        when(observationRepository.findRecentByMarketCnpjRoot(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.brandShare(merchantUser, null, null).brands()).isEmpty();
    }

    // --- analytics (own stores, not K-gated) ---

    private static ReceiptActivityRow activityRow(LocalDateTime issuedAt, String total) {
        return new ReceiptActivityRow() {
            @Override public LocalDateTime getIssuedAt() { return issuedAt; }
            @Override public BigDecimal getTotalAmount() { return new BigDecimal(total); }
        };
    }

    @Test
    void analytics_aggregatesOwnStoreReceiptsDaily() {
        var store = MarketLocation.builder().cnpj("93015006000101").cnpjRoot(CHAIN_ROOT).build();
        when(marketLocationRepository.findAllByCnpjRoot(CHAIN_ROOT)).thenReturn(List.of(store));
        var day = LocalDateTime.of(2026, 11, 10, 9, 0);
        when(receiptRepository.findConfirmedActivityForCnpjs(eq(List.of("93015006000101")), any(LocalDateTime.class)))
                .thenReturn(List.of(activityRow(day, "100.00"), activityRow(day.plusHours(2), "50.00")));

        var response = service.analytics(merchantUser, 30);

        assertThat(response.granularity()).isEqualTo("DAILY");
        assertThat(response.totalReceipts()).isEqualTo(2);
        assertThat(response.totalRevenue()).isEqualByComparingTo("150.00");
        assertThat(response.basketAverage()).isEqualByComparingTo("75.00");
        assertThat(response.series()).hasSize(1);
        assertThat(response.series().get(0).periodStart()).isEqualTo(day.toLocalDate());
        assertThat(response.series().get(0).receipts()).isEqualTo(2);
    }

    @Test
    void analytics_noStores_returnsEmpty() {
        when(marketLocationRepository.findAllByCnpjRoot(CHAIN_ROOT)).thenReturn(List.of());

        var response = service.analytics(merchantUser, null);
        assertThat(response.totalReceipts()).isZero();
        assertThat(response.series()).isEmpty();
    }

    // --- competitor moves (anonymized, K-gated) ---

    private PriceObservation otherObservation(String price, LocalDateTime observedAt) {
        return PriceObservation.builder()
                .product(product).state(STATE)
                .marketCnpjRoot("11111111") // a DIFFERENT chain
                .unitPrice(new BigDecimal(price))
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .build();
    }

    private void stubCompetitor(long recentHouseholds) {
        when(observationRepository.findRecentByMarketCnpjRoot(eq(CHAIN_ROOT), any(LocalDateTime.class)))
                .thenReturn(observations("5.00"));
        var now = LocalDateTime.now();
        var otherObservations = List.of(
                otherObservation("3.00", now), otherObservation("3.00", now.minusDays(1)),
                otherObservation("3.00", now.minusDays(2)),
                otherObservation("4.00", now.minusDays(30)), otherObservation("4.00", now.minusDays(31)),
                otherObservation("4.00", now.minusDays(32)));
        when(observationRepository.findRecentByProductIdsAndStatesExcludingChains(
                anyList(), anyList(), eq(List.of(CHAIN_ROOT)), any(LocalDateTime.class)))
                .thenReturn(otherObservations);
        when(auditRepository.countDistinctHouseholdsPerProductStateExcludingChains(
                anyList(), anyList(), eq(List.of(CHAIN_ROOT)), any(LocalDateTime.class)))
                .thenReturn(List.of(householdCount(product.getId(), STATE, recentHouseholds)));
    }

    @Test
    void competitorMoves_emitsAnonymizedMoveWhenKPasses() {
        stubCompetitor(3);

        var response = service.competitorMoves(merchantUser, null);

        assertThat(response.moves()).hasSize(1);
        var move = response.moves().get(0);
        assertThat(move.productId()).isEqualTo(product.getId());
        assertThat(move.state()).isEqualTo(STATE);
        assertThat(move.direction()).isEqualTo("DOWN");
        assertThat(move.magnitudePercent()).isEqualByComparingTo("25.0");
        assertThat(move.approxDaysAgo()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void competitorMoves_belowKAnonymity_dropsMove() {
        stubCompetitor(2);

        assertThat(service.competitorMoves(merchantUser, null).moves()).isEmpty();
    }

    @Test
    void competitorMoves_payloadNeverCarriesCompetitorIdentity() {
        stubCompetitor(3);

        var move = service.competitorMoves(merchantUser, null).moves().get(0);

        // The anonymized move exposes only product + direction + magnitude + recency — never the
        // competing chain's CNPJ root ("11111111") nor any store name/price (antitrust guardrail).
        assertThat(move.toString()).doesNotContain("11111111");
        assertThat(Stream.of(move.getClass().getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("cnpj", "cnpjRoot", "marketName", "storeName", "price", "unitPrice");
    }
}
