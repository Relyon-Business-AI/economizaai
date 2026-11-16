package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AppSavingSource;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppSavingsServiceTest {

    private static final String MARKET_CNPJ = "12345678000199";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, Month.NOVEMBER, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, Month.NOVEMBER, 16, 23, 59);
    private static final OffsetDateTime RECORDED_AT = LocalDateTime.of(2026, Month.NOVEMBER, 10, 18, 30)
            .atZone(BrazilClock.ZONE).toOffsetDateTime();

    @Mock private NotificationEventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private LocalizedMessageService messageService;
    @InjectMocks private AppSavingsService service;

    private User user;
    private Product coffee;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("APPSAV").build();
        user = User.builder().id(UUID.randomUUID()).email("app-savings-test@economizaai.app").household(household).build();
        coffee = Product.builder().id(UUID.randomUUID()).normalizedName("CAFE TORRADO 500G").build();

        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));
        when(subscriptionGate.clampFrom(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(productRepository.findAllById(anyCollection())).thenReturn(List.of(coffee));
        when(householdProductAliasService.resolvedNamesFor(any(), anyCollection())).thenReturn(Map.of());
        when(marketLocationService.findByCnpjs(anyList()))
                .thenReturn(Map.of(MARKET_CNPJ, MarketLocation.builder().cnpj(MARKET_CNPJ).name("MERCADO X LTDA").build()));
        when(marketNameService.resolveNames(any(), anyCollection())).thenReturn(Map.of());
        when(marketNameService.resolveChainNames(anyCollection())).thenReturn(Map.of());
        when(marketNameService.friendlyName(any(), any(), eq(MARKET_CNPJ), eq("MERCADO X LTDA"))).thenReturn("Mercado X");
        when(messageService.translate("savings.source.DEALS")).thenReturn("visto em Ofertas");
    }

    private NotificationEvent converted(String saved, String paidUnitPrice, String previousLastPaid, String discountFraction) {
        return NotificationEvent.builder()
                .id(UUID.randomUUID()).user(user).eventType(NotificationEventType.CONVERTED)
                .productId(coffee.getId()).marketCnpj(MARKET_CNPJ)
                .savingsAmount(new BigDecimal(saved))
                .metadata("{\"savingsAmount\":" + saved + ",\"paidUnitPrice\":" + paidUnitPrice
                        + ",\"previousLastPaid\":" + previousLastPaid + ",\"discountFraction\":" + discountFraction + "}")
                .occurredAt(RECORDED_AT)
                .build();
    }

    private void householdConverted(NotificationEvent... convertedEvents) {
        when(eventRepository.findConvertedBetween(anyCollection(), any(), any())).thenReturn(List.of(convertedEvents));
    }

    @Test
    void summary_addsThePurchasesOfThePeriodAndNamesTheLargestSaving() {
        householdConverted(
                converted("3.50", "32.90", "36.40", "0.0962"),
                converted("7.20", "17.90", "21.50", "0.1674"));

        var summary = service.summary(user, FROM, TO);

        assertThat(summary.saved()).isEqualByComparingTo("10.70");
        assertThat(summary.purchaseCount()).isEqualTo(2);
        assertThat(summary.largestSaving()).isEqualByComparingTo("7.20");
        assertThat(summary.from()).isEqualTo(FROM);
        assertThat(summary.to()).isEqualTo(TO);
    }

    @Test
    void summary_readsOnlyTheRequestedWindowInBrasiliaTime() {
        householdConverted();

        service.summary(user, FROM, TO);

        verify(eventRepository).findConvertedBetween(List.of(user.getId()),
                FROM.atZone(BrazilClock.ZONE).toOffsetDateTime(), TO.atZone(BrazilClock.ZONE).toOffsetDateTime());
    }

    @Test
    void summary_withoutAStart_coversEverythingOnRecord() {
        householdConverted();

        var summary = service.summary(user, null, TO);

        assertThat(summary.from()).isNull();
        assertThat(summary.saved()).isEqualByComparingTo("0");
        assertThat(summary.purchaseCount()).isZero();
        assertThat(summary.largestSaving()).isEqualByComparingTo("0");
        verify(eventRepository).findConvertedBetween(anyCollection(), isNull(), any());
    }

    @Test
    void summary_respectsTheFreeHistoryWindow() {
        var clampedFrom = TO.minusDays(90);
        when(subscriptionGate.clampFrom(user, null)).thenReturn(clampedFrom);
        householdConverted();

        var summary = service.summary(user, null, TO);

        assertThat(summary.from()).isEqualTo(clampedFrom);
    }

    @Test
    void purchases_comeBiggestSavingFirstAndTiesByBiggestPercentage() {
        var smallSaving = converted("3.50", "32.90", "36.40", "0.0962");
        var tiedLowerPercent = converted("7.20", "40.00", "47.20", "0.1525");
        var tiedHigherPercent = converted("7.20", "17.90", "21.50", "0.1674");
        householdConverted(smallSaving, tiedLowerPercent, tiedHigherPercent);

        var page = service.purchases(user, FROM, TO, 0, 10);

        assertThat(page.getContent()).extracting("id")
                .containsExactly(tiedHigherPercent.getId(), tiedLowerPercent.getId(), smallSaving.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void purchases_carryPricesPercentNamesAndWhereThePriceWasSeen() {
        householdConverted(converted("7.20", "17.90", "21.50", "0.1674"));

        var purchase = service.purchases(user, FROM, TO, 0, 10).getContent().get(0);

        assertThat(purchase.productId()).isEqualTo(coffee.getId());
        assertThat(purchase.productName()).isEqualTo("CAFE TORRADO 500G");
        assertThat(purchase.marketCnpj()).isEqualTo(MARKET_CNPJ);
        assertThat(purchase.marketName()).isEqualTo("MERCADO X LTDA");
        assertThat(purchase.marketFriendlyName()).isEqualTo("Mercado X");
        assertThat(purchase.source()).isEqualTo(AppSavingSource.DEALS);
        assertThat(purchase.sourceLabel()).isEqualTo("visto em Ofertas");
        assertThat(purchase.paidPrice()).isEqualByComparingTo("17.90");
        assertThat(purchase.previousPrice()).isEqualByComparingTo("21.50");
        assertThat(purchase.saved()).isEqualByComparingTo("7.20");
        assertThat(purchase.percent()).isEqualByComparingTo("16.7");
        assertThat(purchase.recordedAt()).isEqualTo(LocalDateTime.of(2026, Month.NOVEMBER, 10, 18, 30));
    }

    @Test
    void purchases_preferTheNameTheHouseholdGaveTheProduct() {
        when(householdProductAliasService.resolvedNamesFor(any(), anyCollection()))
                .thenReturn(Map.of(coffee.getId(), "Café do dia a dia"));
        householdConverted(converted("7.20", "17.90", "21.50", "0.1674"));

        var purchase = service.purchases(user, FROM, TO, 0, 10).getContent().get(0);

        assertThat(purchase.productName()).isEqualTo("Café do dia a dia");
    }

    @Test
    void purchases_areSplitInPagesAndThePageSizeIsBounded() {
        householdConverted(
                converted("9.00", "10.00", "19.00", "0.4737"),
                converted("8.00", "10.00", "18.00", "0.4444"),
                converted("7.00", "10.00", "17.00", "0.4118"));

        var secondPage = service.purchases(user, FROM, TO, 1, 2);
        var oversizedPage = service.purchases(user, FROM, TO, 0, 5000);

        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.getContent().get(0).saved()).isEqualByComparingTo("7.00");
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(oversizedPage.getSize()).isEqualTo(50);
    }

    @Test
    void purchases_withUnreadableMetadata_keepTheSavingAndLeaveThePricesEmpty() {
        var oldEvent = converted("4.00", "1", "1", "0");
        oldEvent.setMetadata("not json");
        householdConverted(oldEvent);

        var purchase = service.purchases(user, FROM, TO, 0, 10).getContent().get(0);

        assertThat(purchase.saved()).isEqualByComparingTo("4.00");
        assertThat(purchase.paidPrice()).isNull();
        assertThat(purchase.previousPrice()).isNull();
        assertThat(purchase.percent()).isNull();
    }

    @Test
    void anEmptyPage_looksUpNoNames() {
        householdConverted();

        var page = service.purchases(user, FROM, TO, 0, 10);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(productRepository, householdProductAliasService, marketLocationService, marketNameService);
    }
}
