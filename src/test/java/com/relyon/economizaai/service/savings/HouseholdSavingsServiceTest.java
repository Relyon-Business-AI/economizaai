package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ContributedPrice;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.PrivacyTierResolver;
import com.relyon.economizaai.service.priceindex.PrivacyTierResolver.Thresholds;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdSavingsServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 31, 23, 59);
    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2026, 10, 15, 18, 0);
    private static final String NEARBY_SUPERMARKET = "11111111000111";
    private static final String NEARBY_PHARMACY = "22222222000111";
    private static final String FARAWAY_SUPERMARKET = "33333333000111";
    private static final BigDecimal HOME_LATITUDE = new BigDecimal("-30.0500000");
    private static final BigDecimal HOME_LONGITUDE = new BigDecimal("-51.2200000");

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private PrivacyTierResolver privacyTierResolver;
    @Mock private SubscriptionGateService subscriptionGate;

    private HouseholdSavingsService service;
    private User user;
    private final Product rice = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
    private final Product medicine = Product.builder().id(UUID.randomUUID()).normalizedName("Remedio").build();
    private final List<ReceiptItem> householdLines = new ArrayList<>();
    private final List<ContributedPrice> contributedPrices = new ArrayList<>();

    private record TestContributedPrice(UUID productId, String marketCnpj, String state, BigDecimal unitPrice,
                                        LocalDateTime observedAt, UUID householdId) implements ContributedPrice {
        @Override public UUID getProductId() { return productId; }
        @Override public String getMarketCnpj() { return marketCnpj; }
        @Override public String getState() { return state; }
        @Override public BigDecimal getUnitPrice() { return unitPrice; }
        @Override public LocalDateTime getObservedAt() { return observedAt; }
        @Override public UUID getHouseholdId() { return householdId; }
    }

    @BeforeEach
    void setUp() {
        service = new HouseholdSavingsService(receiptItemRepository, auditRepository, marketLocationService,
                viewerLocationService, marketNameService, privacyTierResolver, subscriptionGate,
                new CollaborativeProperties());
        user = User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build()).build();

        lenient().when(subscriptionGate.clampFrom(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(receiptItemRepository.findConfirmedInStoreLinesForHousehold(any(), any(), any()))
                .thenReturn(householdLines);
        lenient().when(auditRepository.findContributedPrices(any(), any(), any())).thenReturn(contributedPrices);
        lenient().when(viewerLocationService.resolve(any(), any(), any()))
                .thenReturn(new ViewerLocation(HOME_LATITUDE, HOME_LONGITUDE, Source.PROFILE, "RS"));
        var stores = new HashMap<String, MarketLocation>();
        stores.put(NEARBY_SUPERMARKET, store(NEARBY_SUPERMARKET, MerchantSegment.SUPERMARKET, HOME_LATITUDE, HOME_LONGITUDE));
        stores.put(NEARBY_PHARMACY, store(NEARBY_PHARMACY, MerchantSegment.PHARMACY, HOME_LATITUDE, HOME_LONGITUDE));
        stores.put(FARAWAY_SUPERMARKET, store(FARAWAY_SUPERMARKET, MerchantSegment.SUPERMARKET,
                new BigDecimal("-10.0000000"), new BigDecimal("-40.0000000")));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(stores);
        lenient().when(privacyTierResolver.isSensitiveProduct(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0) == medicine);
        lenient().when(privacyTierResolver.thresholdsFor(anyBoolean()))
                .thenAnswer(invocation -> invocation.<Boolean>getArgument(0) ? new Thresholds(3, 3) : new Thresholds(1, 1));
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolveChainNames(any())).thenReturn(Map.of());
        lenient().when(marketNameService.friendlyName(any(Map.class), any(Map.class), any(), any()))
                .thenReturn("Mercado X");
    }

    @Test
    void boughtBelowWhatOthersPaidNearby_countsAsSaved() {
        buy(rice, "2", "16.00", PURCHASED_AT);
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00", "12.00");

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.saved()).isEqualByComparingTo("4.00");
        assertThat(savings.couldHaveSaved()).isEqualByComparingTo("0.00");
        assertThat(savings.comparedSpend()).isEqualByComparingTo("16.00");
        assertThat(savings.purchasedLineCount()).isEqualTo(1);
        assertThat(savings.comparedLineCount()).isEqualTo(1);
        assertThat(savings.regionComparedLineCount()).isEqualTo(1);
        assertThat(savings.householdComparedLineCount()).isZero();
    }

    @Test
    void boughtAboveWhatOthersPaidNearby_goesToCouldHaveSavedNotToSaved() {
        buy(rice, "1", "13.00", PURCHASED_AT);
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00", "10.00");

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.saved()).isEqualByComparingTo("0.00");
        assertThat(savings.couldHaveSaved()).isEqualByComparingTo("3.00");
    }

    @Test
    void theHouseholdsOwnContributedPrices_neverFormTheRegionReference() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", user.getHousehold().getId(), PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", user.getHousehold().getId(), PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", user.getHousehold().getId(), PURCHASED_AT);

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.comparedLineCount()).isZero();
        assertThat(savings.saved()).isEqualByComparingTo("0.00");
    }

    @Test
    void fewerPricesThanATypicalPriceNeeds_leavesTheLineUncompared() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00");

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.purchasedLineCount()).isEqualTo(1);
        assertThat(savings.comparedLineCount()).isZero();
    }

    @Test
    void pricesFromStoresOutsideTheRadius_areNotTheBuyersRegion() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        othersPaid(rice, FARAWAY_SUPERMARKET, "10.00", "10.00", "10.00");

        assertThat(service.savings(user, FROM, TO).comparedLineCount()).isZero();
    }

    @Test
    void buyerWithoutCoordinates_isComparedWithTheirState() {
        when(viewerLocationService.resolve(any(), any(), any()))
                .thenReturn(new ViewerLocation(null, null, Source.NONE, "RS"));
        buy(rice, "1", "8.00", PURCHASED_AT);
        othersPaid(rice, FARAWAY_SUPERMARKET, "10.00", "10.00", "10.00");

        assertThat(service.savings(user, FROM, TO).saved()).isEqualByComparingTo("2.00");
    }

    @Test
    void pricesFarFromThePurchaseDay_areNotTheSameTime() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        var twoMonthsEarlier = PURCHASED_AT.minusDays(60);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", UUID.randomUUID(), twoMonthsEarlier);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", UUID.randomUUID(), twoMonthsEarlier);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", UUID.randomUUID(), twoMonthsEarlier);

        assertThat(service.savings(user, FROM, TO).comparedLineCount()).isZero();
    }

    @Test
    void kAnonymity_groceryProductAtGroceryStores_needsOnlyOneOtherHousehold() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        var singleNeighbour = UUID.randomUUID();
        contributed(rice, NEARBY_SUPERMARKET, "10.00", singleNeighbour, PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", singleNeighbour, PURCHASED_AT.minusDays(3));
        contributed(rice, NEARBY_SUPERMARKET, "10.00", singleNeighbour, PURCHASED_AT.minusDays(6));

        assertThat(service.savings(user, FROM, TO).regionComparedLineCount()).isEqualTo(1);
    }

    @Test
    void kAnonymity_healthProduct_isNotComparedBelowThreeOtherHouseholds() {
        buy(medicine, "1", "8.00", PURCHASED_AT);
        var firstNeighbour = UUID.randomUUID();
        var secondNeighbour = UUID.randomUUID();
        contributed(medicine, NEARBY_SUPERMARKET, "10.00", firstNeighbour, PURCHASED_AT);
        contributed(medicine, NEARBY_SUPERMARKET, "10.00", firstNeighbour, PURCHASED_AT.minusDays(2));
        contributed(medicine, NEARBY_SUPERMARKET, "10.00", secondNeighbour, PURCHASED_AT);

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.comparedLineCount()).isZero();
        assertThat(savings.saved()).isEqualByComparingTo("0.00");
    }

    @Test
    void kAnonymity_healthProduct_isComparedFromThreeOtherHouseholds() {
        buy(medicine, "1", "8.00", PURCHASED_AT);
        othersPaid(medicine, NEARBY_SUPERMARKET, "10.00", "10.00", "10.00");

        assertThat(service.savings(user, FROM, TO).saved()).isEqualByComparingTo("2.00");
    }

    @Test
    void kAnonymity_pricesSeenAtAPharmacy_keepTheFullKEvenForAGroceryProduct() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        var singleNeighbour = UUID.randomUUID();
        contributed(rice, NEARBY_PHARMACY, "10.00", singleNeighbour, PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", singleNeighbour, PURCHASED_AT);
        contributed(rice, NEARBY_SUPERMARKET, "10.00", singleNeighbour, PURCHASED_AT);

        assertThat(service.savings(user, FROM, TO).comparedLineCount()).isZero();
    }

    @Test
    void withoutARegionReference_theHouseholdsOwnEarlierPricesAreTheReference() {
        buy(rice, "1", "10.00", PURCHASED_AT.minusDays(40));
        buy(rice, "1", "12.00", PURCHASED_AT.minusDays(20));
        buy(rice, "1", "9.00", PURCHASED_AT);

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.purchasedLineCount()).isEqualTo(1);
        assertThat(savings.householdComparedLineCount()).isEqualTo(1);
        assertThat(savings.saved()).isEqualByComparingTo("2.00");
    }

    @Test
    void aSingleEarlierPurchase_isNotAUsualPrice() {
        buy(rice, "1", "12.00", PURCHASED_AT.minusDays(20));
        buy(rice, "1", "9.00", PURCHASED_AT);

        assertThat(service.savings(user, FROM, TO).comparedLineCount()).isZero();
    }

    @Test
    void aPriceSeveralTimesAwayFromItsReference_isAMismatchNotASaving() {
        buy(rice, "1", "2.00", PURCHASED_AT);
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00", "10.00");

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.comparedLineCount()).isZero();
        assertThat(savings.saved()).isEqualByComparingTo("0.00");
    }

    @Test
    void savingsAreBrokenDownByMarket() {
        buy(rice, "1", "8.00", PURCHASED_AT);
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00", "10.00");

        var markets = service.savings(user, FROM, TO).markets();

        assertThat(markets).hasSize(1);
        assertThat(markets.get(0).cnpj()).isEqualTo(NEARBY_SUPERMARKET);
        assertThat(markets.get(0).marketFriendlyName()).isEqualTo("Mercado X");
        assertThat(markets.get(0).saved()).isEqualByComparingTo("2.00");
        assertThat(markets.get(0).comparedLineCount()).isEqualTo(1);
    }

    @Test
    void freeHistoryWindow_limitsHowFarBackThePeriodStarts() {
        var allowedStart = LocalDateTime.of(2026, 10, 10, 0, 0);
        when(subscriptionGate.clampFrom(user, FROM)).thenReturn(allowedStart);
        buy(rice, "1", "8.00", LocalDateTime.of(2026, 10, 5, 12, 0));
        othersPaid(rice, NEARBY_SUPERMARKET, "10.00", "10.00", "10.00");

        var savings = service.savings(user, FROM, TO);

        assertThat(savings.from()).isEqualTo(allowedStart);
        assertThat(savings.purchasedLineCount()).isZero();
    }

    @Test
    void nothingBought_readsNoCommunityPrices() {
        var savings = service.savings(user, FROM, TO);

        assertThat(savings.saved()).isEqualByComparingTo("0.00");
        assertThat(savings.markets()).isEmpty();
        verify(auditRepository, never()).findContributedPrices(any(), any(), any());
    }

    @Test
    void withoutAStart_thePeriodIsTheLastNinetyDays() {
        service.savings(user, null, TO);

        verify(receiptItemRepository).findConfirmedInStoreLinesForHousehold(
                eq(user.getHousehold().getId()), eq(TO.minusDays(180)), eq(TO));
    }

    private void buy(Product product, String quantity, String totalPrice, LocalDateTime purchasedAt) {
        var receipt = Receipt.builder().id(UUID.randomUUID())
                .cnpjEmitente(NEARBY_SUPERMARKET).marketName("MERCADO X LTDA").issuedAt(purchasedAt).build();
        householdLines.add(ReceiptItem.builder()
                .receipt(receipt).product(product).lineNumber(1).rawDescription("item")
                .quantity(new BigDecimal(quantity)).totalPrice(new BigDecimal(totalPrice))
                .build());
    }

    /** One price per distinct other household, all on the purchase day. */
    private void othersPaid(Product product, String marketCnpj, String... unitPrices) {
        for (var unitPrice : unitPrices) {
            contributed(product, marketCnpj, unitPrice, UUID.randomUUID(), PURCHASED_AT);
        }
    }

    private void contributed(Product product, String marketCnpj, String unitPrice, UUID householdId, LocalDateTime observedAt) {
        contributedPrices.add(new TestContributedPrice(product.getId(), marketCnpj, "RS",
                new BigDecimal(unitPrice), observedAt.toLocalDate().atStartOfDay(), householdId));
    }

    private MarketLocation store(String cnpj, MerchantSegment segment, BigDecimal latitude, BigDecimal longitude) {
        return MarketLocation.builder().cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8)).name("Loja").segment(segment)
                .latitude(latitude).longitude(longitude).build();
    }
}
