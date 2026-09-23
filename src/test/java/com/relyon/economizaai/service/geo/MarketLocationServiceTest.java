package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.geo.NominatimGeocoder.GeocodeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketLocationServiceTest {

    @Mock private MarketLocationRepository repository;
    @Mock private NominatimGeocoder geocoder;
    @Mock private CnpjActivityClient cnpjActivityClient;
    @Mock private ProductRepository productRepository;
    @Mock private ContactService contactService;

    private MarketLocationService service;

    @BeforeEach
    void setUp() {
        service = new MarketLocationService(repository, geocoder, cnpjActivityClient, productRepository,
                contactService);
    }

    // ---------- registerMarketFromReceipt ----------

    @Test
    void registerMarketFromReceipt_skipsWhenCnpjNull() {
        var receipt = receipt(null, "Mercado", "Rua A");

        service.registerMarketFromReceipt(receipt);

        verify(repository, never()).findByCnpj(any());
        verify(repository, never()).save(any());
    }

    @Test
    void registerMarketFromReceipt_skipsWhenAlreadyRegistered() {
        var receipt = receipt("11111111000111", "Mercado", "Rua A");
        when(repository.findByCnpj("11111111000111"))
                .thenReturn(Optional.of(new MarketLocation()));

        service.registerMarketFromReceipt(receipt);

        verify(repository, never()).save(any());
    }

    @Test
    void registerMarketFromReceipt_savesNewLocationWithCnpjRoot() {
        var receipt = receipt("11111111000199", "Zaffari Centro", "Av Brasil, 100");
        when(repository.findByCnpj("11111111000199")).thenReturn(Optional.empty());

        service.registerMarketFromReceipt(receipt);

        var saved = ArgumentCaptor.forClass(MarketLocation.class);
        verify(repository).save(saved.capture());
        var location = saved.getValue();
        assertThat(location.getCnpj()).isEqualTo("11111111000199");
        assertThat(location.getCnpjRoot()).isEqualTo("11111111");
        assertThat(location.getName()).isEqualTo("Zaffari Centro");
        assertThat(location.getAddress()).isEqualTo("Av Brasil, 100");
    }

    // ---------- geocodeOne ----------

    @Test
    void geocodeOne_onHit_setsCoordinatesAndClearsFailure() {
        var market = MarketLocation.builder()
                .cnpj("11111111000111").cnpjRoot("11111111")
                .address("Av Brasil, 100").geocodeAttempts(0).build();
        market.setGeocodeFailedAt(LocalDateTime.now());
        when(geocoder.geocode(any())).thenReturn(Optional.of(
                new GeocodeResult(new BigDecimal("-30.05"), new BigDecimal("-51.22"), "Porto Alegre", "RS")));
        when(repository.findById(any())).thenReturn(Optional.of(market));

        service.geocodeOne(market);

        assertThat(market.getGeocodeAttempts()).isEqualTo(1);
        assertThat(market.getLatitude()).isEqualByComparingTo("-30.05");
        assertThat(market.getLongitude()).isEqualByComparingTo("-51.22");
        assertThat(market.getCity()).isEqualTo("Porto Alegre");
        assertThat(market.getState()).isEqualTo("RS");
        assertThat(market.getGeocodedAt()).isNotNull();
        assertThat(market.getGeocodeFailedAt()).isNull();
        verify(repository).save(market);
    }

    @Test
    void geocodeOne_onMiss_recordsFailureAndIncrementsAttempts() {
        var market = MarketLocation.builder()
                .cnpj("11111111000111").cnpjRoot("11111111")
                .address("Nowhere").geocodeAttempts(1).build();
        when(geocoder.geocode(any())).thenReturn(Optional.empty());
        when(repository.findById(any())).thenReturn(Optional.of(market));

        service.geocodeOne(market);

        assertThat(market.getGeocodeAttempts()).isEqualTo(2);
        assertThat(market.getLatitude()).isNull();
        assertThat(market.getGeocodeFailedAt()).isNotNull();
        assertThat(market.getGeocodedAt()).isNull();
        verify(repository).save(market);
    }

    // ---------- geocodePending ----------

    @Test
    void geocodePending_noPending_doesNothing() {
        when(repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(anyInt()))
                .thenReturn(List.of());

        service.geocodePending();

        verify(geocoder, never()).geocode(any());
    }

    @Test
    void geocodePending_geocodesEachPendingMarket() {
        var marketOne = MarketLocation.builder().cnpj("1").cnpjRoot("11111111").address("A").build();
        var marketTwo = MarketLocation.builder().cnpj("2").cnpjRoot("22222222").address("B").build();
        when(repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(anyInt()))
                .thenReturn(List.of(marketOne, marketTwo));
        when(geocoder.geocode(any())).thenReturn(Optional.empty());
        when(repository.findById(any())).thenReturn(Optional.of(marketOne), Optional.of(marketTwo));

        service.geocodePending();

        verify(geocoder, times(2)).geocode(any());
        verify(repository, times(2)).save(any());
    }

    // ---------- buildGeocodeQuery (via geocodeOne, asserting the query passed) ----------

    @Test
    void buildGeocodeQuery_prefersAddress() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .address("Rua X").name("Nome").build();
        when(geocoder.geocode("Rua X, Brasil")).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode("Rua X, Brasil");
    }

    @Test
    void buildGeocodeQuery_fallsBackToNameWhenAddressBlank() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .address("  ").name("Mercado Nome").build();
        when(geocoder.geocode("Mercado Nome, Brasil")).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode("Mercado Nome, Brasil");
    }

    @Test
    void buildGeocodeQuery_fallsBackToCnpjWhenAddressAndNameMissing() {
        var market = MarketLocation.builder().cnpj("11111111000111").cnpjRoot("11111111")
                .address(null).name(null).build();
        when(geocoder.geocode("CNPJ 11111111000111, Brasil")).thenReturn(Optional.empty());

        service.geocodeOne(market);

        verify(geocoder).geocode("CNPJ 11111111000111, Brasil");
    }

    // ---------- classifyPendingSegments ----------

    @Test
    void classifyPendingSegments_returnsZeroSummaryWhenDisabled() {
        when(cnpjActivityClient.isEnabled()).thenReturn(false);

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isZero();
        verify(repository, never()).findAllBySegmentAndSegmentAttemptsLessThan(any(), anyInt());
    }

    @Test
    void classifyPendingSegments_returnsZeroSummaryWhenNoPending() {
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(repository.findAllBySegmentAndSegmentAttemptsLessThan(eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of());

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isZero();
        verify(cnpjActivityClient, never()).lookup(any());
    }

    @Test
    void classifyPendingSegments_talliesEachSegment() {
        var pharmacyMarket = MarketLocation.builder().cnpj("p").cnpjRoot("pppppppp").build();
        var supermarketMarket = MarketLocation.builder().cnpj("s").cnpjRoot("ssssssss").build();
        var otherMarket = MarketLocation.builder().cnpj("o").cnpjRoot("oooooooo").build();
        var unknownMarket = MarketLocation.builder().cnpj("u").cnpjRoot("uuuuuuuu").build();
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(repository.findAllBySegmentAndSegmentAttemptsLessThan(eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of(pharmacyMarket, supermarketMarket, otherMarket, unknownMarket));
        when(cnpjActivityClient.lookup("p")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.PHARMACY, null, List.of("4771701")));
        when(cnpjActivityClient.lookup("s")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, null, List.of("4711302")));
        when(cnpjActivityClient.lookup("o")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.OTHER, null, List.of("9999999")));
        when(cnpjActivityClient.lookup("u")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.UNKNOWN, null, List.of()));
        when(productRepository.findOtherCategoryProductsByMerchant("p")).thenReturn(List.of());

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isEqualTo(4);
        assertThat(summary.pharmacy()).isEqualTo(1);
        assertThat(summary.supermarket()).isEqualTo(1);
        assertThat(summary.other()).isEqualTo(1);
        assertThat(summary.stillUnknown()).isEqualTo(1);
    }

    // ---------- classifySegmentOne ----------

    @Test
    void classifySegmentOne_setsSegmentWhenResolved() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("c")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, null, List.of("4711302")));

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.SUPERMARKET);
        assertThat(market.getSegmentAttempts()).isEqualTo(1);
        assertThat(market.getSegmentClassifiedAt()).isNotNull();
        verify(repository).save(market);
        verify(productRepository, never()).findOtherCategoryProductsByMerchant(any());
    }

    @Test
    void classifySegmentOne_leavesUnknownWhenUnresolved() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(2).build();
        when(cnpjActivityClient.lookup("c")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.UNKNOWN, null, List.of()));

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.UNKNOWN);
        assertThat(market.getSegmentAttempts()).isEqualTo(3);
        assertThat(market.getSegmentClassifiedAt()).isNull();
        verify(repository).save(market);
    }

    @Test
    void classifySegmentOne_pharmacy_backfillsOtherProducts() {
        var market = MarketLocation.builder().cnpj("ph").cnpjRoot("pppppppp")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        var productOne = Product.builder().normalizedName("dipirona")
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.NONE).build();
        var productTwo = Product.builder().normalizedName("paracetamol")
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.NONE).build();
        when(cnpjActivityClient.lookup("ph")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.PHARMACY, null, List.of("4771701")));
        when(productRepository.findOtherCategoryProductsByMerchant("ph"))
                .thenReturn(List.of(productOne, productTwo));

        service.classifySegmentOne(market);

        assertThat(productOne.getCategory()).isEqualTo(ProductCategory.HEALTH);
        assertThat(productOne.getCategorizationSource()).isEqualTo(CategorizationSource.MERCHANT);
        assertThat(productTwo.getCategory()).isEqualTo(ProductCategory.HEALTH);
        verify(productRepository).saveAll(anyList());
    }

    @Test
    void classifySegmentOne_pharmacyWithNoProducts_doesNotSaveAll() {
        var market = MarketLocation.builder().cnpj("ph").cnpjRoot("pppppppp")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("ph")).thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.PHARMACY, null, List.of("4771701")));
        when(productRepository.findOtherCategoryProductsByMerchant("ph")).thenReturn(List.of());

        service.classifySegmentOne(market);

        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void classifySegmentOne_persistsCnaeCodes() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("c")).thenReturn(
                new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, null, List.of("4711302", "4729699")));

        service.classifySegmentOne(market);

        assertThat(market.getCnaeCodes()).isEqualTo("4711302,4729699");
    }

    @Test
    void classifySegmentOne_greyMerchant_notifiesAdminOnce() {
        var market = MarketLocation.builder().cnpj("g").cnpjRoot("gggggggg")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("g")).thenReturn(
                new CnpjActivityClient.CnpjLookup(MerchantSegment.OTHER, null, List.of("9999999")));

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.OTHER);
        assertThat(market.getGraySightingNotifiedAt()).isNotNull();
        verify(contactService).notifyAdmin(any(), any());
    }

    @Test
    void classifySegmentOne_greyMerchantAlreadyNotified_doesNotNotifyAgain() {
        var market = MarketLocation.builder().cnpj("g").cnpjRoot("gggggggg")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(1)
                .graySightingNotifiedAt(LocalDateTime.now().minusDays(1)).build();
        when(cnpjActivityClient.lookup("g")).thenReturn(
                new CnpjActivityClient.CnpjLookup(MerchantSegment.OTHER, null, List.of("9999999")));

        service.classifySegmentOne(market);

        verifyNoInteractions(contactService);
    }

    @Test
    void classifySegmentOne_foodServiceMerchant_doesNotNotifyAdmin() {
        var market = MarketLocation.builder().cnpj("f").cnpjRoot("ffffffff")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("f")).thenReturn(
                new CnpjActivityClient.CnpjLookup(MerchantSegment.FOOD_SERVICE, null, List.of("5611203")));

        service.classifySegmentOne(market);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.FOOD_SERVICE);
        verifyNoInteractions(contactService);
    }

    // ---------- resolveForIngest ----------

    @Test
    void resolveForIngest_nullCnpj_returnsNull() {
        assertThat(service.resolveForIngest(null, "Bar", "Rua B")).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void resolveForIngest_knownClassifiedMarket_skipsLookup() {
        var market = MarketLocation.builder().cnpj("11111111000111").cnpjRoot("11111111")
                .segment(MerchantSegment.SUPERMARKET).build();
        when(repository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));

        var resolved = service.resolveForIngest("11111111000111", "Zaffari", "Av Brasil");

        assertThat(resolved).isSameAs(market);
        verify(cnpjActivityClient, never()).lookup(any());
    }

    @Test
    void resolveForIngest_newMarket_registersAndClassifiesInline() {
        when(repository.findByCnpj("22222222000122")).thenReturn(Optional.empty());
        when(repository.save(any(MarketLocation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(cnpjActivityClient.lookup("22222222000122")).thenReturn(
                new CnpjActivityClient.CnpjLookup(MerchantSegment.FOOD_SERVICE, null, List.of("5611203")));

        var resolved = service.resolveForIngest("22222222000122", "Bar do Zé", "Rua B");

        assertThat(resolved.getSegment()).isEqualTo(MerchantSegment.FOOD_SERVICE);
        assertThat(resolved.getName()).isEqualTo("Bar do Zé");
    }

    @Test
    void classifySegmentOne_capturesIbgeCityCode() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0).build();
        when(cnpjActivityClient.lookup("c"))
                .thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, "4314902", List.of("4711302")));

        service.classifySegmentOne(market);

        assertThat(market.getIbgeCityCode()).isEqualTo("4314902");
        verify(repository).save(market);
    }

    @Test
    void classifySegmentOne_neverOverwritesExistingIbgeCityCode() {
        var market = MarketLocation.builder().cnpj("c").cnpjRoot("cccccccc")
                .segment(MerchantSegment.UNKNOWN).segmentAttempts(0)
                .ibgeCityCode("4314902").build();
        when(cnpjActivityClient.lookup("c"))
                .thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, "9999999", List.of("4711302")));

        service.classifySegmentOne(market);

        assertThat(market.getIbgeCityCode()).isEqualTo("4314902");
    }

    @Test
    void classifyPendingSegments_includesIbgeBackfillRows() {
        // already classified before the IBGE column existed → picked up by backfill
        var classified = MarketLocation.builder().cnpj("b").cnpjRoot("bbbbbbbb")
                .segment(MerchantSegment.SUPERMARKET).segmentAttempts(1).build();
        when(cnpjActivityClient.isEnabled()).thenReturn(true);
        when(repository.findAllBySegmentAndSegmentAttemptsLessThan(eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of());
        when(repository.findAllByIbgeCityCodeIsNullAndSegmentNotAndSegmentAttemptsLessThan(
                eq(MerchantSegment.UNKNOWN), anyInt()))
                .thenReturn(List.of(classified));
        when(cnpjActivityClient.lookup("b"))
                .thenReturn(new CnpjActivityClient.CnpjLookup(MerchantSegment.SUPERMARKET, "4314902", List.of("4711302")));

        var summary = service.classifyPendingSegments();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(classified.getIbgeCityCode()).isEqualTo("4314902");
    }

    // ---------- findByCnpjs ----------

    @Test
    void findByCnpjs_nullReturnsEmptyMap() {
        assertThat(service.findByCnpjs(null)).isEqualTo(Map.of());
        verifyNoInteractions(repository);
    }

    @Test
    void findByCnpjs_emptyReturnsEmptyMap() {
        assertThat(service.findByCnpjs(List.of())).isEqualTo(Map.of());
        verifyNoInteractions(repository);
    }

    @Test
    void findByCnpjs_indexesByCnpj() {
        var marketOne = MarketLocation.builder().cnpj("11111111000111").cnpjRoot("11111111").build();
        var marketTwo = MarketLocation.builder().cnpj("22222222000111").cnpjRoot("22222222").build();
        when(repository.findAllByCnpjIn(List.of("11111111000111", "22222222000111")))
                .thenReturn(List.of(marketOne, marketTwo));

        var byCnpj = service.findByCnpjs(List.of("11111111000111", "22222222000111"));

        assertThat(byCnpj).hasSize(2);
        assertThat(byCnpj.get("11111111000111")).isSameAs(marketOne);
        assertThat(byCnpj.get("22222222000111")).isSameAs(marketTwo);
    }

    @Test
    void scheduledSegmentClassification_delegatesToClassifyPending() {
        when(cnpjActivityClient.isEnabled()).thenReturn(false);

        service.scheduledSegmentClassification();

        verify(cnpjActivityClient).isEnabled();
    }

    private Receipt receipt(String cnpj, String name, String address) {
        var receipt = new Receipt();
        receipt.setCnpjEmitente(cnpj);
        receipt.setMarketName(name);
        receipt.setMarketAddress(address);
        return receipt;
    }
}
