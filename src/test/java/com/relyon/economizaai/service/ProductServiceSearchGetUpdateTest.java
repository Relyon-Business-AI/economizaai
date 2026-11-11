package com.relyon.economizaai.service;

import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.dto.request.CreateProductRequest;
import com.relyon.economizaai.dto.request.UpdateProductRequest;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.extraction.BrandAliasPromotionService;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.ProductExtraction;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProductServiceSearchGetUpdateTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryResolver categoryResolver;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ProductExtractor productExtractor;
    @Mock private EanCatalogService eanCatalogService;
    @Mock private BrandAliasPromotionService brandAliasPromotionService;
    @Mock private ViewerLocationService viewerLocationService;

    private ProductService productService;

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var householdProductAliasService =
                new HouseholdProductAliasService(householdProductAliasRepository, receiptItemRepository);
        productService = new ProductService(productRepository, categoryResolver, aliasRepository, householdProductAliasRepository,
                receiptItemRepository, priceObservationRepository, productExtractor, eanCatalogService,
                householdProductAliasService, brandAliasPromotionService, viewerLocationService);
        lenient().when(viewerLocationService.resolve(any())).thenAnswer(invocation -> {
            User viewer = invocation.getArgument(0);
            var source = viewer.getHomeLatitude() != null ? Source.PROFILE : Source.NONE;
            return new ViewerLocation(viewer.getHomeLatitude(), viewer.getHomeLongitude(), source, null);
        });
    }

    private User user() {
        var household = Household.builder().id(HOUSEHOLD_ID).inviteCode("SEARCH").build();
        return User.builder().id(UUID.randomUUID()).email("search@test.com").household(household).build();
    }

    private User userWithHome() {
        var user = user();
        user.setHomeLatitude(new BigDecimal("-30.0000000"));
        user.setHomeLongitude(new BigDecimal("-51.0000000"));
        return user;
    }

    private Product buildProduct(UUID id) {
        return buildProduct(id, "Arroz Tio Joao 5kg");
    }

    private Product buildProduct(UUID id, String name) {
        return Product.builder()
                .id(id)
                .ean("7891234567890")
                .normalizedName(name)
                .genericName("Arroz")
                .brand("Tio Joao")
                .category(ProductCategory.GROCERIES)
                .unit("UN")
                .packSize(new BigDecimal("5.000"))
                .packUnit("KG")
                .categorizationSource(CategorizationSource.USER)
                .build();
    }

    @Test
    void search_trimsQueryAndMapsResults() {
        var pageable = PageRequest.of(0, 20);
        var product = buildProduct(UUID.randomUUID());
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(product));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());

        var result = productService.search("  arroz  ", user(), pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("Arroz Tio Joao 5kg", result.getContent().get(0).normalizedName());
        assertNull(result.getContent().get(0).friendlyDescription());
        assertFalse(result.getContent().get(0).hasPriceHistory());
        verify(productRepository).searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class));
    }

    @Test
    void search_includesHouseholdFriendlyRenameWhenPresent() {
        var pageable = PageRequest.of(0, 20);
        var id = UUID.randomUUID();
        var product = buildProduct(id);
        var alias = HouseholdProductAlias.builder().product(product).friendlyName("Arroz Comum").build();
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(product));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(eq(HOUSEHOLD_ID), anyList()))
                .thenReturn(List.of(alias));

        var result = productService.search("arroz", user(), pageable);

        assertEquals("Arroz Comum", result.getContent().get(0).friendlyDescription());
        assertEquals("Arroz Tio Joao 5kg", result.getContent().get(0).normalizedName());
    }

    @Test
    void search_fallsBackToReceiptFriendlyNameWhenNoExplicitAlias() {
        // Bug: a product renamed only via a confirmed receipt item (e.g. added
        // through ReceiptService.addItem, which never creates a household alias)
        // must still show its friendly name in search results, like it already
        // does on the shopping list / receipt screens.
        var pageable = PageRequest.of(0, 20);
        var id = UUID.randomUUID();
        var product = buildProduct(id);
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(product));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(eq(HOUSEHOLD_ID), anyList()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.<Object[]>of(new Object[]{id, "Arroz da nota"}));

        var result = productService.search("arroz", user(), pageable);

        assertEquals("Arroz da nota", result.getContent().get(0).friendlyDescription());
    }

    @Test
    void search_marksHasPriceHistoryWhenHouseholdHasBoughtIt() {
        var pageable = PageRequest.of(0, 20);
        var id = UUID.randomUUID();
        var product = buildProduct(id);
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(product));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of(id));
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());

        var result = productService.search("arroz", user(), pageable);

        assertTrue(result.getContent().get(0).hasPriceHistory());
    }

    @Test
    void search_noHistoryForHouseholdStaysFalse() {
        var pageable = PageRequest.of(0, 20);
        var id = UUID.randomUUID();
        var product = buildProduct(id);
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(product));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());

        var result = productService.search("arroz", user(), pageable);

        assertFalse(result.getContent().get(0).hasPriceHistory());
    }

    @Test
    void search_includesProductsMatchedByHouseholdFriendlyName() {
        var pageable = PageRequest.of(0, 20);
        var catalogMatch = buildProduct(UUID.randomUUID(), "Arroz Tio Joao 5kg");
        var renamed = buildProduct(UUID.randomUUID(), "Cafe Melitta 500g");
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(catalogMatch));
        // "renamed" only matches via the household alias; "catalogMatch" matches both ways and must not duplicate.
        when(householdProductAliasRepository.findProductsByFriendlyNameContaining(HOUSEHOLD_ID, "arroz"))
                .thenReturn(List.of(renamed, catalogMatch));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());

        var result = productService.search("arroz", user(), pageable);

        assertEquals(2, result.getTotalElements());
        var returnedIds = result.getContent().stream().map(response -> response.id()).toList();
        assertTrue(returnedIds.contains(renamed.getId()));
        assertTrue(returnedIds.contains(catalogMatch.getId()));
    }

    @Test
    void search_blankQuery_returnsTheHouseholdsOwnProductsMostPurchasedFirst_neverTheCatalog() {
        var pageable = PageRequest.of(0, 20);
        var mostBought = buildProduct(UUID.randomUUID(), "Leite Integral 1L");
        var lessBought = buildProduct(UUID.randomUUID(), "Arroz Tio Joao 5kg");
        when(receiptItemRepository.findMostPurchasedProductIdsForHousehold(HOUSEHOLD_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(mostBought.getId(), lessBought.getId()), pageable, 37));
        when(productRepository.findAllById(List.of(mostBought.getId(), lessBought.getId())))
                .thenReturn(List.of(lessBought, mostBought)); // DB order is arbitrary

        var result = productService.search("   ", user(), pageable);

        assertEquals(37, result.getTotalElements());
        assertEquals(List.of("Leite Integral 1L", "Arroz Tio Joao 5kg"),
                result.getContent().stream().map(product -> product.normalizedName()).toList());
        assertTrue(result.getContent().stream().allMatch(product -> product.hasPriceHistory()));
        verify(productRepository, never()).searchCatalogByNameOrEan(any(), any(), any());
        verify(productRepository, never()).searchCatalogByAliasDescription(any(), any());
    }

    @Test
    void search_blankQuery_dropsAClientSortBeforeItReachesTheQuery() {
        var sortedByClient = PageRequest.of(2, 10, Sort.by("normalizedName"));
        when(receiptItemRepository.findMostPurchasedProductIdsForHousehold(HOUSEHOLD_ID, PageRequest.of(2, 10)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        var result = productService.search(null, user(), sortedByClient);

        assertEquals(0, result.getTotalElements());
        verify(receiptItemRepository).findMostPurchasedProductIdsForHousehold(HOUSEHOLD_ID, PageRequest.of(2, 10));
    }

    @Test
    void search_singleCharacter_searchesOnlyTheHouseholdsOwnProducts() {
        var pageable = PageRequest.of(0, 20);
        var owned = buildProduct(UUID.randomUUID(), "Arroz Tio Joao 5kg");
        when(productRepository.searchPurchasedByHousehold(eq(HOUSEHOLD_ID), eq("a"), eq("%a%"), any(Pageable.class)))
                .thenReturn(List.of(owned));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of(owned.getId()));

        var result = productService.search("a", user(), pageable);

        assertEquals(1, result.getTotalElements());
        verify(productRepository, never()).searchCatalogByNameOrEan(any(), any(), any());
        verify(productRepository, never()).searchCatalogByAliasDescription(any(), any());
    }

    @Test
    void search_asksEachCatalogSourceForACappedCandidateSet() {
        var pageable = PageRequest.of(0, 20);
        var limitCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), limitCaptor.capture()))
                .thenReturn(List.of());

        productService.search("arroz", user(), pageable);

        assertEquals(PageRequest.of(0, 200), limitCaptor.getValue());
        verify(productRepository).searchCatalogByAliasDescription(eq("%arroz%"), eq(PageRequest.of(0, 200)));
    }

    @Test
    void search_pagesOverTheRankedCandidates() {
        var first = buildProduct(UUID.randomUUID(), "Arroz A");
        var second = buildProduct(UUID.randomUUID(), "Arroz B");
        var third = buildProduct(UUID.randomUUID(), "Arroz C");
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class)))
                .thenReturn(List.of(third, first, second));

        var secondPage = productService.search("arroz", user(), PageRequest.of(1, 2));

        assertEquals(3, secondPage.getTotalElements());
        assertEquals(List.of("Arroz C"),
                secondPage.getContent().stream().map(product -> product.normalizedName()).toList());
    }

    @Test
    void search_mergesAliasAndHouseholdMatchesWithoutDuplicates() {
        var pageable = PageRequest.of(0, 20);
        var byName = buildProduct(UUID.randomUUID(), "Arroz Tio Joao 5kg");
        var byAlias = buildProduct(UUID.randomUUID(), "Cereal Branco 5kg");
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class)))
                .thenReturn(List.of(byName));
        when(productRepository.searchCatalogByAliasDescription(eq("%arroz%"), any(Pageable.class)))
                .thenReturn(List.of(byAlias, byName));
        when(productRepository.searchPurchasedByHousehold(eq(HOUSEHOLD_ID), eq("arroz"), eq("%arroz%"), any(Pageable.class)))
                .thenReturn(List.of(byName));

        var result = productService.search("arroz", user(), pageable);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void containsPattern_lowercasesAndEscapesLikeWildcards() {
        assertEquals("%50!% off!_x!!%", ProductService.containsPattern("50% OFF_x!"));
    }

    @Test
    void search_ordersByLocalRelevanceBeforeName() {
        var pageable = PageRequest.of(0, 20);
        var city = buildProduct(UUID.randomUUID(), "Arroz Cidade");
        var bought = buildProduct(UUID.randomUUID(), "Arroz Comprado");
        var nearby = buildProduct(UUID.randomUUID(), "Arroz Proximo");
        var visitedMarket = buildProduct(UUID.randomUUID(), "Arroz Mercado Visitado");
        var unrelated = buildProduct(UUID.randomUUID(), "Arroz Zzz");
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(city, unrelated, nearby, visitedMarket, bought));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of(bought.getId()));
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of(visitedMarket.getId()));
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of(city.getId()));
        var row = mock(PriceObservationRepository.ProductMarketCoordinates.class);
        when(row.getProductId()).thenReturn(nearby.getId());
        when(row.getLatitude()).thenReturn(new BigDecimal("-30.0100000"));
        when(row.getLongitude()).thenReturn(new BigDecimal("-51.0100000"));
        when(priceObservationRepository.findProductMarketCoordinates(anyList())).thenReturn(List.of(row));

        var result = productService.search("arroz", userWithHome(), pageable);

        assertEquals(List.of("Arroz Comprado", "Arroz Mercado Visitado", "Arroz Proximo",
                        "Arroz Cidade", "Arroz Zzz"),
                result.getContent().stream().map(product -> product.normalizedName()).toList());
    }

    @Test
    void search_withoutHomeRanksNearbyFromLocationInferredFromReceipts() {
        var pageable = PageRequest.of(0, 20);
        var nearby = buildProduct(UUID.randomUUID(), "Arroz Proximo");
        var unrelated = buildProduct(UUID.randomUUID(), "Arroz Aaa");
        var viewer = user();
        when(viewerLocationService.resolve(viewer)).thenReturn(new ViewerLocation(
                new BigDecimal("-30.0000000"), new BigDecimal("-51.0000000"), Source.RECEIPTS_MEDIAN, "RS"));
        when(productRepository.searchCatalogByNameOrEan(eq("arroz"), eq("%arroz%"), any(Pageable.class))).thenReturn(List.of(unrelated, nearby));
        when(receiptItemRepository.findProductIdsWithHistoryForHousehold(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedAtVisitedMarkets(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        when(priceObservationRepository.findProductIdsObservedInHouseholdCities(anyList(), eq(HOUSEHOLD_ID)))
                .thenReturn(List.of());
        var row = mock(PriceObservationRepository.ProductMarketCoordinates.class);
        when(row.getProductId()).thenReturn(nearby.getId());
        when(row.getLatitude()).thenReturn(new BigDecimal("-30.0100000"));
        when(row.getLongitude()).thenReturn(new BigDecimal("-51.0100000"));
        when(priceObservationRepository.findProductMarketCoordinates(anyList())).thenReturn(List.of(row));

        var result = productService.search("arroz", viewer, pageable);

        assertEquals(List.of("Arroz Proximo", "Arroz Aaa"),
                result.getContent().stream().map(product -> product.normalizedName()).toList());
    }

    @Test
    void get_returnsMappedProduct() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(buildProduct(id)));

        var response = productService.get(id, user());

        assertEquals(id, response.id());
        assertEquals("Tio Joao", response.brand());
        assertEquals(ProductCategory.GROCERIES, response.category());
    }

    @Test
    void get_throwsWhenMissing() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.get(id, user()));
    }

    @Test
    void update_setsFieldsAndMarksSourceUserWhenCategoryProvided() {
        var id = UUID.randomUUID();
        var existing = buildProduct(id);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateProductRequest(
                "Arroz Tio Joao 1kg", "Arroz Branco", "Tio Joao",
                ProductCategory.GROCERIES, "UN", new BigDecimal("1.000"), "KG");

        var response = productService.update(id, request);

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals("Arroz Tio Joao 1kg", saved.getValue().getNormalizedName());
        assertEquals("Arroz Branco", saved.getValue().getGenericName());
        assertEquals(ProductCategory.GROCERIES, saved.getValue().getCategory());
        assertEquals(CategorizationSource.USER, saved.getValue().getCategorizationSource());
        assertEquals("Arroz Tio Joao 1kg", response.normalizedName());
    }

    @Test
    void update_populatesNormalizedMirrorsKeepingDisplayValues() {
        var id = UUID.randomUUID();
        var existing = buildProduct(id);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateProductRequest(
                "Cafe Pilao", "Café", "Pilão",
                ProductCategory.GROCERIES, "UN", new BigDecimal("500.000"), "G");

        productService.update(id, request);

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals("Café", saved.getValue().getGenericName(), "display generic name kept");
        assertEquals("Pilão", saved.getValue().getBrand(), "display brand kept");
        assertEquals("cafe", saved.getValue().getGenericNameNorm(), "generic name mirror normalized");
        assertEquals("pilao", saved.getValue().getBrandNorm(), "brand mirror normalized");
    }

    @Test
    void update_blankOptionalFieldsBecomeNullAndSourceNoneWhenCategoryNull() {
        var id = UUID.randomUUID();
        var existing = buildProduct(id);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateProductRequest(
                "Arroz", "   ", "", null, "  ", null, "");

        productService.update(id, request);

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertNull(saved.getValue().getGenericName());
        assertNull(saved.getValue().getBrand());
        assertNull(saved.getValue().getCategory());
        assertNull(saved.getValue().getUnit());
        assertNull(saved.getValue().getPackUnit());
        assertEquals(CategorizationSource.NONE, saved.getValue().getCategorizationSource());
    }

    @Test
    void update_throwsWhenProductMissing() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        var request = new UpdateProductRequest("X", null, null, null, null, null, null);
        assertThrows(ProductNotFoundException.class, () -> productService.update(id, request));
        verify(productRepository, never()).save(any());
    }

    @Test
    void create_withoutEan_skipsBackfillAndUsesExtractedFallbacks() {
        var extracted = new ProductExtraction(
                "Cerveja", "Stella", new BigDecimal("330"), "ML",
                ProductCategory.BEVERAGES, null, CategorizationSource.DICTIONARY);
        when(productExtractor.extract("Cerveja Stella Artois 330ml")).thenReturn(extracted);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(UUID.randomUUID());
            return product;
        });

        var request = new CreateProductRequest(
                "  ", "Cerveja Stella Artois 330ml", null, null, null, "UN", null, null);

        var response = productService.create(request);

        assertNull(response.ean());
        assertEquals("Cerveja", response.genericName());
        assertEquals("Stella", response.brand());
        assertEquals(ProductCategory.BEVERAGES, response.category());
        assertEquals(CategorizationSource.DICTIONARY, response.categorizationSource());
        verify(receiptItemRepository, never()).linkByEan(any(Product.class), any());
    }

    @Test
    void get_carriesCallerHouseholdFriendlyName() {
        var id = UUID.randomUUID();
        var caller = user();
        when(productRepository.findById(id)).thenReturn(Optional.of(buildProduct(id)));
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(eq(caller.getHousehold().getId()), anyList()))
                .thenReturn(List.of(HouseholdProductAlias.builder()
                        .household(caller.getHousehold())
                        .product(buildProduct(id))
                        .friendlyName("Arroz da casa")
                        .build()));

        var response = productService.get(id, caller);

        assertEquals("Arroz da casa", response.friendlyDescription());
    }

    @Test
    void get_fallsBackToReceiptFriendlyNameWhenNoExplicitAlias() {
        var id = UUID.randomUUID();
        var caller = user();
        when(productRepository.findById(id)).thenReturn(Optional.of(buildProduct(id)));
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(eq(caller.getHousehold().getId()), anyList()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(caller.getHousehold().getId())))
                .thenReturn(List.<Object[]>of(new Object[]{id, "Arroz da nota"}));

        var response = productService.get(id, caller);

        assertEquals("Arroz da nota", response.friendlyDescription());
    }
}
