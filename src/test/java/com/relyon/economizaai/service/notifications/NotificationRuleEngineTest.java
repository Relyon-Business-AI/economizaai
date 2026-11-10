package com.relyon.economizaai.service.notifications;
import org.mockito.Spy;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRuleEngineTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private NotificationService notificationService;
    @Mock private NearbyScopeService nearbyScopeService;
    private static LocalizedMessageService realMessageService() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return new LocalizedMessageService(source);
    }

    @Spy private LocalizedMessageService messageService = realMessageService();
    @InjectMocks private NotificationRuleEngine engine;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(nearbyScopeService.forUser(any())).thenAnswer(invocation ->
                scopeFromHome(invocation.getArgument(0), null, Set.of()));
    }

    /** Explicit home when set, else unlocated; {@code uf} null = no state known (national). */
    private static NearbyScope scopeFromHome(User user, String uf, Set<String> watched) {
        var source = user.getHomeLatitude() != null ? Source.PROFILE : Source.NONE;
        return new NearbyScope(new ViewerLocation(user.getHomeLatitude(), user.getHomeLongitude(), source, uf),
                watched, 10);
    }

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID OWNER_HOUSEHOLD = UUID.randomUUID();
    private static final UUID CONTRIBUTOR_HOUSEHOLD = UUID.randomUUID();
    private static final String CNPJ = "12345678000199";

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite").build();
    }

    private User owner(BigDecimal latitude, BigDecimal longitude) {
        return User.builder().id(UUID.randomUUID()).email("owner@example.com")
                .household(Household.builder().id(OWNER_HOUSEHOLD).build())
                .homeLatitude(latitude).homeLongitude(longitude)
                .build();
    }

    private NotificationRule priceRule(NotificationType type, BigDecimal threshold,
                                       Double radiusKm, LocalDateTime lastFired, User user) {
        return NotificationRule.builder().id(UUID.randomUUID())
                .user(user).type(type).product(product())
                .thresholdPrice(threshold).radiusKm(radiusKm)
                .active(true).lastFiredAt(lastFired)
                .build();
    }

    private PriceObservation observation(BigDecimal unitPrice) {
        return observationIn(unitPrice, null);
    }

    private PriceObservation observationIn(BigDecimal unitPrice, String uf) {
        return PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName("Zaffari").unitPrice(unitPrice).quantity(BigDecimal.ONE)
                .state(uf).observedAt(LocalDateTime.now()).build();
    }

    private MarketLocation marketAt(BigDecimal latitude, BigDecimal longitude) {
        return MarketLocation.builder().cnpj(CNPJ).latitude(latitude).longitude(longitude).build();
    }

    // ---- PRICE_DROP ----

    @Test
    void priceDrop_firesWhenObservedAtOrBelowThreshold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.49"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(NotificationType.PRICE_DROP, captor.getValue().type());
        // localized to the recipient's default (pt) locale
        assertTrue(captor.getValue().title().startsWith("Preço baixou:"), captor.getValue().title());
        verify(ruleRepository).saveAll(any());
    }

    @Test
    void priceDrop_notificationLocalizedToUserLocale_en() {
        var enUser = owner(null, null);
        enUser.setLocale("en");
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, enUser);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.49"))), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertTrue(captor.getValue().title().startsWith("Price drop:"), captor.getValue().title());
        assertTrue(captor.getValue().body().contains("You asked to be alerted below"), captor.getValue().body());
    }

    @Test
    void priceDrop_marketNameFallbackLocalizedToUserLocale_en() {
        var enUser = owner(null, null);
        enUser.setLocale("en");
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, enUser);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        var namelessMarket = PriceObservation.builder()
                .product(product()).marketCnpj(CNPJ).marketCnpjRoot("12345678")
                .marketName(null).unitPrice(new BigDecimal("5.49")).quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now()).build();

        engine.evaluate(List.of(namelessMarket), CONTRIBUTOR_HOUSEHOLD);

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        // The fallback market name follows the recipient's locale — never hardcoded pt.
        assertTrue(captor.getValue().body().contains("a nearby market"), captor.getValue().body());
    }

    @Test
    void priceDrop_doesNotFireAboveThreshold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("6.50"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_doesNotFireInsideCooldown() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null,
                LocalDateTime.now().minusHours(1), owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_doesNotFireForContributingHousehold() {
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, owner(null, null));
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), OWNER_HOUSEHOLD);

        verify(notificationService, never()).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_doesNotFireWhenMarketOutsideRadius() {
        var home = owner(new BigDecimal("-30.0331"), new BigDecimal("-51.2300"));
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), 5.0, null, home);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        // São Paulo ~850 km from Porto Alegre
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(new BigDecimal("-23.5505"), new BigDecimal("-46.6333"))));

        engine.evaluate(List.of(observation(new BigDecimal("5.00"))), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notifyAfterCommit(any());
    }

    // ---- "perto de você" scope ----

    private static final BigDecimal PORTO_ALEGRE_LAT = new BigDecimal("-30.0331");
    private static final BigDecimal PORTO_ALEGRE_LNG = new BigDecimal("-51.2300");
    private static final BigDecimal SAO_PAULO_LAT = new BigDecimal("-23.5505");
    private static final BigDecimal SAO_PAULO_LNG = new BigDecimal("-46.6333");

    @Test
    void priceDrop_ruleWithoutRadiusStillNeverFiresAcrossTheCountry() {
        var home = owner(PORTO_ALEGRE_LAT, PORTO_ALEGRE_LNG);
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, home);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(SAO_PAULO_LAT, SAO_PAULO_LNG)));

        engine.evaluate(List.of(observationIn(new BigDecimal("5.00"), "SP")), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService, never()).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_firesWithinDefaultRadius() {
        var home = owner(PORTO_ALEGRE_LAT, PORTO_ALEGRE_LNG);
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, home);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(new BigDecimal("-30.0400"), new BigDecimal("-51.2200"))));

        engine.evaluate(List.of(observationIn(new BigDecimal("5.00"), "RS")), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_watchedMarketFiresEvenWhenFar() {
        var home = owner(PORTO_ALEGRE_LAT, PORTO_ALEGRE_LNG);
        when(nearbyScopeService.forUser(home)).thenReturn(scopeFromHome(home, "RS", Set.of(CNPJ)));
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, home);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of(CNPJ, marketAt(SAO_PAULO_LAT, SAO_PAULO_LNG)));

        engine.evaluate(List.of(observationIn(new BigDecimal("5.00"), "SP")), CONTRIBUTOR_HOUSEHOLD);

        verify(notificationService).notifyAfterCommit(any());
    }

    @Test
    void priceDrop_unlocatedUserOnlyGetsTheirOwnState() {
        var user = owner(null, null);
        when(nearbyScopeService.forUser(user)).thenReturn(scopeFromHome(user, "RS", Set.of()));
        var rule = priceRule(NotificationType.PRICE_DROP, new BigDecimal("6.00"), null, null, user);
        when(ruleRepository.findActiveProductRules(any(), any())).thenReturn(List.of(rule));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        engine.evaluate(List.of(observationIn(new BigDecimal("5.00"), "PE")), CONTRIBUTOR_HOUSEHOLD);
        verify(notificationService, never()).notifyAfterCommit(any());

        engine.evaluate(List.of(observationIn(new BigDecimal("5.00"), "RS")), CONTRIBUTOR_HOUSEHOLD);
        verify(notificationService).notifyAfterCommit(any());
    }
}
