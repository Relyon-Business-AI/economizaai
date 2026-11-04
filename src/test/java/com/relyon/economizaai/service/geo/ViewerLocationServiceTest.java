package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViewerLocationServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private MarketLocationRepository marketLocationRepository;
    @InjectMocks private ViewerLocationService service;

    private User user;
    private UUID householdId;

    @BeforeEach
    void setUp() {
        householdId = UUID.randomUUID();
        var household = Household.builder().build();
        household.setId(householdId);
        user = User.builder().household(household).build();
        lenient().when(receiptRepository.findConfirmedUfsForHousehold(householdId))
                .thenReturn(List.of(UnidadeFederativa.RS, UnidadeFederativa.SC));
    }

    @Test
    void explicitLocationWins() {
        user.setHomeLatitude(new BigDecimal("-30.00"));
        user.setHomeLongitude(new BigDecimal("-51.00"));

        var location = service.resolve(user);

        assertEquals(Source.EXPLICIT, location.source());
        assertEquals(new BigDecimal("-30.00"), location.latitude());
        assertEquals("RS", location.uf());
    }

    @Test
    void infersMedianCentroidFromGeocodedMarkets() {
        when(receiptRepository.findDistinctCnpjsByHousehold(householdId))
                .thenReturn(List.of("111", "222", "333"));
        when(marketLocationRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market(new BigDecimal("-30.00"), new BigDecimal("-51.00")),
                market(new BigDecimal("-30.10"), new BigDecimal("-51.10")),
                market(new BigDecimal("-30.20"), new BigDecimal("-51.20"))));

        var location = service.resolve(user);

        assertEquals(Source.INFERRED, location.source());
        // mediana dos 3 pontos = o do meio.
        assertEquals(0, new BigDecimal("-30.10").compareTo(location.latitude()));
        assertEquals(0, new BigDecimal("-51.10").compareTo(location.longitude()));
        assertEquals("RS", location.uf());
    }

    @Test
    void noGeocodedMarkets_isNoneButKeepsUf() {
        when(receiptRepository.findDistinctCnpjsByHousehold(householdId)).thenReturn(List.of("111"));
        when(marketLocationRepository.findAllByCnpjIn(any()))
                .thenReturn(List.of(market(null, null))); // sem coordenadas

        var location = service.resolve(user);

        assertEquals(Source.NONE, location.source());
        assertTrue(!location.hasCoordinates());
        assertEquals("RS", location.uf());
    }

    @Test
    void noReceiptsAtAll_isNoneWithNullUf() {
        when(receiptRepository.findConfirmedUfsForHousehold(householdId)).thenReturn(List.of());
        when(receiptRepository.findDistinctCnpjsByHousehold(householdId)).thenReturn(List.of());

        var location = service.resolve(user);

        assertEquals(Source.NONE, location.source());
        assertNull(location.uf());
    }

    private MarketLocation market(BigDecimal latitude, BigDecimal longitude) {
        return MarketLocation.builder().latitude(latitude).longitude(longitude).build();
    }
}
