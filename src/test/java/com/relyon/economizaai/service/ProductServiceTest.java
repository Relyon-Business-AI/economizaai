package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.CreateAliasRequest;
import com.relyon.economizaai.dto.request.CreateProductRequest;
import com.relyon.economizaai.exception.EanConflictException;
import com.relyon.economizaai.exception.ProductAliasConflictException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.ProductExtraction;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ProductExtractor productExtractor;
    @Mock private EanCatalogService eanCatalogService;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    @InjectMocks private ProductService productService;

    @Test
    void lookupByEan_returnsTrackedProductWhenKnown() {
        var product = Product.builder().id(UUID.randomUUID()).ean("7891234567890")
                .normalizedName("ARROZ TIO J 5KG").category(ProductCategory.GROCERIES).build();
        when(productRepository.findByEan("7891234567890")).thenReturn(Optional.of(product));

        var result = productService.lookupByEan("7891234567890", buildUser());

        assertEquals(true, result.known());
        assertEquals(product.getId(), result.product().id());
        assertNull(result.catalogPreview());
    }

    @Test
    void lookupByEan_fallsBackToCatalogPreview() {
        when(productRepository.findByEan("7891234567890")).thenReturn(Optional.empty());
        when(eanCatalogService.lookup("7891234567890")).thenReturn(Optional.of(
                EanCatalogEntry.builder().ean("7891234567890").genericName("Arroz")
                        .brand("Tio João").category(ProductCategory.GROCERIES).build()));

        var result = productService.lookupByEan("7891234567890", buildUser());

        assertEquals(false, result.known());
        assertNull(result.product());
        assertEquals("Tio João", result.catalogPreview().brand());
        assertEquals("GROCERIES", result.catalogPreview().category());
    }

    @Test
    void lookupByEan_stripsNonDigitsBeforeLookup() {
        var product = Product.builder().id(UUID.randomUUID()).ean("7891234567890").build();
        when(productRepository.findByEan("7891234567890")).thenReturn(Optional.of(product));

        var result = productService.lookupByEan(" 789.1234.5678-90 ", buildUser());

        assertEquals(true, result.known());
    }

    @Test
    void lookupByEan_unknownBarcodeThrows404() {
        when(productRepository.findByEan("7899999999999")).thenReturn(Optional.empty());
        when(eanCatalogService.lookup("7899999999999")).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.lookupByEan("7899999999999", buildUser()));
    }

    @Test
    void lookupByEan_tooShortInputThrows404WithoutQuerying() {
        assertThrows(ProductNotFoundException.class, () -> productService.lookupByEan("123", buildUser()));

        verify(productRepository, never()).findByEan(any());
    }

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private ReceiptItem itemFor(Receipt receipt, String desc, String ean) {
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(desc)
                .ean(ean)
                .quantity(BigDecimal.ONE)
                .totalPrice(new BigDecimal("9.99"))
                .build();
        receipt.addItem(item);
        return item;
    }

    @Test
    void createProduct_savesAndBackfillsByEan() {
        var request = new CreateProductRequest("789", "Arroz Tio Joao", null, "Tio Joao", ProductCategory.GROCERIES, "UN", null, null);
        when(productExtractor.extract(any())).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.findByEan("789")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var p = inv.<Product>getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(receiptItemRepository.linkByEan(any(Product.class), any())).thenReturn(3);

        var response = productService.create(request);

        assertEquals("Arroz Tio Joao", response.normalizedName());
        verify(receiptItemRepository).linkByEan(any(Product.class), any());
    }

    @Test
    void createProduct_rejectsDuplicateEan() {
        var request = new CreateProductRequest("789", "Arroz", null, null, null, null, null, null);
        when(productRepository.findByEan("789")).thenReturn(Optional.of(Product.builder().id(UUID.randomUUID()).build()));

        assertThrows(EanConflictException.class, () -> productService.create(request));
        verify(productRepository, never()).save(any());
    }

    @Test
    void addAlias_persistsAndBackfillsMatchingItems() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Banana").build();
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(user.getHousehold()).build();
        var matching = itemFor(receipt, "BANANA CATURRA KG", null);
        var nonMatching = itemFor(receipt, "MAMAO PAPAYA KG", null);

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);
        when(receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()))
                .thenReturn(List.of(matching, nonMatching));

        productService.addAlias(user, product.getId(), new CreateAliasRequest("Banana Caturra KG"));

        verify(aliasRepository).save(any(ProductAlias.class));
        verify(receiptItemRepository, times(1)).save(matching);
        verify(receiptItemRepository, never()).save(nonMatching);
        assertEquals(product, matching.getProduct());
    }

    @Test
    void addAlias_throwsWhenDuplicate() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.existsByNormalizedDescription("arroz tio joao")).thenReturn(true);
        var productId = product.getId();
        var request = new CreateAliasRequest("ARROZ TIO JOAO");

        assertThrows(ProductAliasConflictException.class,
                () -> productService.addAlias(user, productId, request));
    }

    @Test
    void addAlias_throwsWhenProductMissing() {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        var request = new CreateAliasRequest("ARROZ");

        assertThrows(ProductNotFoundException.class,
                () -> productService.addAlias(user, id, request));
    }

    @Test
    void createProduct_userCategoryWinsOverExtractorAndMarksSourceUser() {
        var request = new CreateProductRequest(null, "Arroz Tio Joao", null, null, ProductCategory.GROCERIES, null, null, null);
        // Extractor disagrees — the user's explicit pick must win, with source USER.
        when(productExtractor.extract("Arroz Tio Joao")).thenReturn(new ProductExtraction(
                "Arroz", "Tio Joao", null, null, ProductCategory.OTHER, CategorizationSource.DICTIONARY));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var saved = inv.<Product>getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        var response = productService.create(request);

        assertEquals(ProductCategory.GROCERIES, response.category());
        assertEquals(CategorizationSource.USER, response.categorizationSource());
    }

    @Test
    void createProduct_noCategoryAnywhere_setsSourceNone() {
        var request = new CreateProductRequest(null, "Produto Misterioso", null, null, null, null, null, null);
        when(productExtractor.extract("Produto Misterioso")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var saved = inv.<Product>getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        var response = productService.create(request);

        assertNull(response.category());
        assertEquals(CategorizationSource.NONE, response.categorizationSource());
        verify(receiptItemRepository, never()).linkByEan(any(Product.class), any());
    }

    @Test
    void createProduct_blankEan_skipsDuplicateCheckAndBackfill() {
        var request = new CreateProductRequest("   ", "Arroz", null, null, null, null, null, null);
        when(productExtractor.extract("Arroz")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var saved = inv.<Product>getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        var response = productService.create(request);

        assertNull(response.ean());
        verify(productRepository, never()).findByEan(any());
        verify(receiptItemRepository, never()).linkByEan(any(Product.class), any());
    }

    @Test
    void addAlias_descriptionNormalizingToBlank_throwsConflict() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var productId = product.getId();
        // Only symbols/accents-free junk: normalizes to "" and must be rejected before any save.
        var request = new CreateAliasRequest("!!! ### ***");

        assertThrows(ProductAliasConflictException.class,
                () -> productService.addAlias(user, productId, request));
        verify(aliasRepository, never()).save(any());
        verify(aliasRepository, never()).existsByNormalizedDescription(any());
    }

    @Test
    void listUnmatched_emptyWhenHouseholdHasNoUnmatchedItems() {
        var user = buildUser();
        when(receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()))
                .thenReturn(List.of());

        assertEquals(List.of(), productService.listUnmatched(user));
    }

    @Test
    void listUnmatched_returnsCurrentHouseholdsItems() {
        var user = buildUser();
        var receipt = Receipt.builder().id(UUID.randomUUID()).marketName("Mercado X")
                .user(user).household(user.getHousehold()).build();
        var item = itemFor(receipt, "ITEM X", null);
        when(receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()))
                .thenReturn(List.of(item));

        var unmatched = productService.listUnmatched(user);

        assertEquals(1, unmatched.size());
        assertEquals("ITEM X", unmatched.get(0).rawDescription());
        assertEquals("Mercado X", unmatched.get(0).marketName());
    }
}
