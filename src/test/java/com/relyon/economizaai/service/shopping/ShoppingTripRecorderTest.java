package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.dto.request.RecordShoppingTripRequest;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem.PriceSource;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ShoppingTrip;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.model.enums.ShoppingTripOutcome;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ShoppingTripRepository;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.BasketComparisonService;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.AlternativeLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.MarketAlternative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShoppingTripRecorderTest {

    private static final String CLIENT_TRIP_ID = "trip-from-device-1";
    private static final ViewerLocation HOME = new ViewerLocation(
            new BigDecimal("-30.03"), new BigDecimal("-51.21"), Source.PROFILE, "RS");

    @Mock private ShoppingTripRepository tripRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BasketComparisonService basketComparisonService;
    @Mock private ViewerLocationService viewerLocationService;
    @Captor private ArgumentCaptor<Collection<BasketLine>> basketCaptor;

    private ShoppingTripRecorder recorder;
    private Household household;
    private User user;
    private UUID milkId;
    private UUID bananaId;

    @BeforeEach
    void setUp() {
        recorder = new ShoppingTripRecorder(tripRepository, receiptRepository, productRepository,
                basketComparisonService, viewerLocationService);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("buyer@economizai").household(household).build();
        milkId = UUID.randomUUID();
        bananaId = UUID.randomUUID();
        lenient().when(tripRepository.findByHouseholdIdAndClientTripId(any(), any())).thenReturn(Optional.empty());
        lenient().when(tripRepository.save(any(ShoppingTrip.class))).thenAnswer(invocation -> {
            ShoppingTrip trip = invocation.getArgument(0);
            trip.setId(UUID.randomUUID());
            return trip;
        });
        lenient().when(productRepository.findAllById(any())).thenReturn(List.of(product(milkId), product(bananaId)));
        lenient().when(viewerLocationService.resolve(any())).thenReturn(HOME);
        lenient().when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void record_storesPlanStopsAndItemsInRouteOrder() {
        var request = request(null,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, false, null),
                item(bananaId, "1", "6.90", ShoppingTripItemStatus.NOT_FOUND, false, null));

        recorder.record(user, request);

        var trip = savedTrip();
        assertEquals(household.getId(), trip.getHouseholdId());
        assertEquals(user.getId(), trip.getUserId());
        assertEquals(CLIENT_TRIP_ID, trip.getClientTripId());
        assertEquals(ShoppingTripOutcome.COMPLETED, trip.getOutcome());
        assertEquals(new BigDecimal("115.42"), trip.getPlannedTotal());
        assertEquals(2, trip.getMaxStops());
        var stop = trip.getStops().get(0);
        assertEquals(0, stop.getPosition());
        assertEquals("11111111000111", stop.getMarketCnpj());
        // 6 x 5.29 + 1 x 6.90
        assertEquals(new BigDecimal("38.64"), stop.getPlannedTotal());
        assertEquals(2, stop.getItems().size());
        assertEquals(ShoppingTripItemStatus.NOT_FOUND, stop.getItems().get(1).getStatus());
        assertEquals(PriceSource.LOCAL_HISTORY, stop.getItems().get(0).getPriceSource());
    }

    @Test
    void record_linksReceiptOfTheHouseholdAndKeepsPaidPrices() {
        var receiptId = UUID.randomUUID();
        when(receiptRepository.findAllById(any())).thenReturn(List.of(receipt(receiptId, household)));
        var request = request(receiptId,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, true, "5.14"));

        recorder.record(user, request);

        var stop = savedTrip().getStops().get(0);
        assertEquals(receiptId, stop.getReceiptId());
        assertEquals(new BigDecimal("30.84"), stop.getReceiptPaidTotal());
        assertTrue(stop.getItems().get(0).isInReceipt());
        assertEquals(new BigDecimal("5.14"), stop.getItems().get(0).getPaidUnitPrice());
    }

    @Test
    void record_dropsReceiptLinkAndPaidPricesWhenReceiptBelongsToAnotherHousehold() {
        var receiptId = UUID.randomUUID();
        var otherHousehold = Household.builder().id(UUID.randomUUID()).inviteCode("ZZZ999").build();
        when(receiptRepository.findAllById(any())).thenReturn(List.of(receipt(receiptId, otherHousehold)));
        var request = request(receiptId,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, true, "5.14"));

        recorder.record(user, request);

        var stop = savedTrip().getStops().get(0);
        assertNull(stop.getReceiptId());
        assertNull(stop.getReceiptPaidTotal());
        assertFalse(stop.getItems().get(0).isInReceipt());
        assertNull(stop.getItems().get(0).getPaidUnitPrice());
    }

    @Test
    void record_leavesOutItemsOfProductsThatNoLongerExist() {
        when(productRepository.findAllById(any())).thenReturn(List.of(product(milkId)));
        var request = request(null,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, false, null),
                item(bananaId, "1", "6.90", ShoppingTripItemStatus.IN_CART, false, null));

        recorder.record(user, request);

        var stop = savedTrip().getStops().get(0);
        assertEquals(1, stop.getItems().size());
        assertEquals(milkId, stop.getItems().get(0).getProductId());
        assertEquals(new BigDecimal("31.74"), stop.getPlannedTotal());
    }

    @Test
    void record_storesWhatTheBoughtItemsWouldCostAtSingleNearbyMarkets() {
        when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of(
                new MarketAlternative("22222222000122", "22222222", "Atacado Sul", 1.2,
                        new BigDecimal("31.74"), new BigDecimal("28.74"), List.of(
                        new AlternativeLine(milkId, null, new BigDecimal("6"), new BigDecimal("31.74"),
                                new BigDecimal("4.79"), new BigDecimal("28.74"), null)))));
        var request = request(null,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, false, null),
                item(bananaId, "1", "6.90", ShoppingTripItemStatus.NOT_FOUND, false, null));

        recorder.record(user, request);

        // Only what was bought is compared, and a single-stop trip leaves its own market out.
        verify(basketComparisonService).compare(eq(HOME), basketCaptor.capture(), eq(Set.of("11111111")), eq(3));
        var bought = List.copyOf(basketCaptor.getValue());
        assertEquals(1, bought.size());
        assertEquals(milkId, bought.get(0).productId());
        assertEquals(new BigDecimal("31.74"), bought.get(0).lineTotal());
        var alternative = savedTrip().getAlternatives().get(0);
        assertEquals("22222222000122", alternative.getMarketCnpj());
        assertEquals(1, alternative.getMatchedItemCount());
        assertEquals(1, alternative.getBoughtItemCount());
        assertEquals(new BigDecimal("31.74"), alternative.getBasketTotal());
        assertEquals(new BigDecimal("28.74"), alternative.getAlternativeTotal());
        assertEquals(new BigDecimal("1.200"), alternative.getDistanceKm());
    }

    @Test
    void record_stillStoresTheTripWhenTheComparisonFails() {
        when(basketComparisonService.compare(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("price index unavailable"));

        recorder.record(user, request(null,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, false, null)));

        assertTrue(savedTrip().getAlternatives().isEmpty());
    }

    @Test
    void record_isIdempotentForTheSameClientTripId() {
        var existing = ShoppingTrip.builder().id(UUID.randomUUID()).build();
        when(tripRepository.findByHouseholdIdAndClientTripId(household.getId(), CLIENT_TRIP_ID))
                .thenReturn(Optional.of(existing));

        var tripId = recorder.record(user, request(null,
                item(milkId, "6", "5.29", ShoppingTripItemStatus.IN_CART, false, null)));

        assertEquals(existing.getId(), tripId);
        verify(tripRepository, never()).save(any());
    }

    private ShoppingTrip savedTrip() {
        var captor = ArgumentCaptor.forClass(ShoppingTrip.class);
        verify(tripRepository).save(captor.capture());
        return captor.getValue();
    }

    private RecordShoppingTripRequest request(UUID receiptId, RecordShoppingTripRequest.Item... items) {
        var stop = new RecordShoppingTripRequest.Stop("11111111000111", "Mercado do Bairro",
                new BigDecimal("0.4"), true, OffsetDateTime.now(), receiptId,
                receiptId == null ? null : new BigDecimal("30.84"),
                receiptId == null ? null : new BigDecimal("31.74"), List.of(items));
        return new RecordShoppingTripRequest(CLIENT_TRIP_ID, UUID.randomUUID(),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now(), ShoppingTripOutcome.COMPLETED,
                new BigDecimal("10"), 2, new BigDecimal("10"), new BigDecimal("115.42"), 2, 0,
                new BigDecimal("1.20"), 3, List.of(stop));
    }

    private RecordShoppingTripRequest.Item item(UUID productId, String quantity, String plannedUnitPrice,
                                                ShoppingTripItemStatus status, boolean inReceipt,
                                                String paidUnitPrice) {
        return new RecordShoppingTripRequest.Item(productId, new BigDecimal(quantity),
                new BigDecimal(plannedUnitPrice), PriceSource.LOCAL_HISTORY, status, inReceipt,
                paidUnitPrice == null ? null : new BigDecimal(paidUnitPrice), 0, false);
    }

    private Product product(UUID productId) {
        return Product.builder().id(productId).normalizedName("Produto").build();
    }

    private Receipt receipt(UUID receiptId, Household owner) {
        return Receipt.builder().id(receiptId).household(owner).build();
    }
}
