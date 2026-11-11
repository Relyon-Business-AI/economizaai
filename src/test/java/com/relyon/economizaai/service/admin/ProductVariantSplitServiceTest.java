package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductVariantSplitServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private BrandExtractor brandExtractor;

    @InjectMocks private ProductVariantSplitService service;

    private static final String NESTLE_EAN = "7891000126905";
    private static final String DALIA_EAN = "7898681790401";
    private static final String CRYSTAL_EAN_A = "7896011511234";
    private static final String CRYSTAL_EAN_B = "7896011519999";

    @BeforeEach
    void brandStubs() {
        // VariantDiscriminator is brand-blind; the service adds the brand via BrandExtractor.
        lenient().when(brandExtractor.find("CR LTE NESTLE 200G")).thenReturn("Nestlé");
        lenient().when(brandExtractor.find("CR LTE DALIA 200G")).thenReturn("Dália");
        lenient().when(brandExtractor.find("AGUA CRYSTAL S GAS 500ML")).thenReturn("Crystal");
    }

    private Product original(String ean) {
        return Product.builder()
                .id(UUID.randomUUID())
                .ean(ean)
                .normalizedName("CR LTE NESTLE 200G")
                .genericName("Creme de leite")
                .genericNameNorm("creme de leite")
                .category(ProductCategory.GROCERIES)
                .build();
    }

    private ReceiptItem item(Product product, String rawDescription, String ean, String unitPrice, UUID receiptId) {
        var receipt = Receipt.builder().id(receiptId).build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .product(product)
                .receipt(receipt)
                .lineNumber(1)
                .rawDescription(rawDescription)
                .ean(ean)
                .quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal(unitPrice))
                .totalPrice(new BigDecimal(unitPrice))
                .build();
    }

    @Test
    void twoBrandsUnderOneProduct_splitsIntoTwo() {
        var product = original(NESTLE_EAN);
        var nestleItem = item(product, "CR LTE NESTLE 200G", NESTLE_EAN, "3.49", UUID.randomUUID());
        var daliaItem = item(product, "CR LTE DALIA 200G", DALIA_EAN, "2.99", UUID.randomUUID());

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId()))
                .thenReturn(List.of(nestleItem, daliaItem));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(List.of());
        // New product gets a fresh EAN (no other product owns it).
        when(productRepository.findByEan(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            var toSave = invocation.getArgument(0, Product.class);
            if (toSave.getId() == null) toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var response = service.splitVariants(500, false);

        assertEquals(1, response.productsSplit());
        assertEquals(1, response.newProductsCreated());
        assertFalse(response.dryRun());

        var plan = response.splits().get(0);
        assertEquals("creme de leite", plan.genericNameNorm());
        assertEquals(2, plan.resultingProducts().size());

        // One new Product persisted, carrying the SAME generic_name_norm (type grouping preserved).
        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals("creme de leite", saved.getValue().getGenericNameNorm());
        assertNotNull(saved.getValue().getBrand());

        // The non-anchor signature's item was repointed to the new product.
        verify(receiptItemRepository).repointItemsTo(anyList(), any(Product.class));
    }

    @Test
    void oneSignatureTwoEans_isNotSplit() {
        var product = Product.builder()
                .id(UUID.randomUUID())
                .ean(CRYSTAL_EAN_A)
                .normalizedName("AGUA CRYSTAL S GAS 500ML")
                .genericName("Água")
                .genericNameNorm("agua")
                .category(ProductCategory.BEVERAGES)
                .build();
        var itemA = item(product, "AGUA CRYSTAL S GAS 500ML", CRYSTAL_EAN_A, "1.50", UUID.randomUUID());
        var itemB = item(product, "AGUA CRYSTAL S GAS 500ML", CRYSTAL_EAN_B, "1.50", UUID.randomUUID());

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId()))
                .thenReturn(List.of(itemA, itemB));

        var response = service.splitVariants(500, false);

        assertEquals(0, response.productsSplit(), "same brand + no distinguishing token = one signature");
        assertEquals(0, response.newProductsCreated());
        assertTrue(response.splits().isEmpty());
        verify(productRepository, never()).save(any(Product.class));
        verify(receiptItemRepository, never()).repointItemsTo(anyList(), any(Product.class));
    }

    @Test
    void observationsReassignedToCorrectSplitProduct() {
        var product = original(NESTLE_EAN);
        var nestleReceipt = UUID.randomUUID();
        var daliaReceipt = UUID.randomUUID();
        var nestleItem = item(product, "CR LTE NESTLE 200G", NESTLE_EAN, "3.49", nestleReceipt);
        var daliaItem = item(product, "CR LTE DALIA 200G", DALIA_EAN, "2.99", daliaReceipt);

        var nestleObservationId = UUID.randomUUID();
        var daliaObservationId = UUID.randomUUID();
        // [observationId, receiptId, unitPrice, quantity] — traced to the item via receipt + price + qty.
        var trace = List.of(
                new Object[]{nestleObservationId, nestleReceipt, new BigDecimal("3.49"), new BigDecimal("1.000")},
                new Object[]{daliaObservationId, daliaReceipt, new BigDecimal("2.99"), new BigDecimal("1.000")});

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId()))
                .thenReturn(List.of(nestleItem, daliaItem));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(trace);
        when(productRepository.findByEan(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            var toSave = invocation.getArgument(0, Product.class);
            if (toSave.getId() == null) toSave.setId(UUID.randomUUID());
            return toSave;
        });

        service.splitVariants(500, false);

        // Nestlé is the anchor (its EAN is the product's own EAN) → stays on the original.
        // Dália is the new product → its ONE observation is the one reassigned.
        var movedIds = ArgumentCaptor.forClass(Collection.class);
        verify(priceObservationRepository).repointObservationsTo(movedIds.capture(), any(Product.class));
        assertEquals(List.of(daliaObservationId), List.copyOf(movedIds.getValue()));
    }

    @Test
    void dryRunMutatesNothing() {
        var product = original(NESTLE_EAN);
        var nestleItem = item(product, "CR LTE NESTLE 200G", NESTLE_EAN, "3.49", UUID.randomUUID());
        var daliaItem = item(product, "CR LTE DALIA 200G", DALIA_EAN, "2.99", UUID.randomUUID());

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId()))
                .thenReturn(List.of(nestleItem, daliaItem));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(List.of());

        var response = service.splitVariants(500, true);

        assertTrue(response.dryRun());
        assertEquals(1, response.productsSplit());
        assertEquals(1, response.newProductsCreated());
        verify(productRepository, never()).save(any(Product.class));
        verify(receiptItemRepository, never()).repointItemsTo(anyList(), any(Product.class));
        verify(priceObservationRepository, never()).repointObservationsTo(any(), any(Product.class));
        verify(aliasRepository, never()).repointAliasesTo(any(), any(Product.class));
    }

    @Test
    void aliasesReassignedBySignature() {
        var product = original(NESTLE_EAN);
        var nestleItem = item(product, "CR LTE NESTLE 200G", NESTLE_EAN, "3.49", UUID.randomUUID());
        var daliaItem = item(product, "CR LTE DALIA 200G", DALIA_EAN, "2.99", UUID.randomUUID());
        var nestleAlias = ProductAlias.builder().id(UUID.randomUUID()).product(product)
                .rawDescription("CR LTE NESTLE 200G").normalizedDescription("creme leite nestle 200 g").build();
        var daliaAlias = ProductAlias.builder().id(UUID.randomUUID()).product(product)
                .rawDescription("CR LTE DALIA 200G").normalizedDescription("creme leite dalia 200 g").build();

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId()))
                .thenReturn(List.of(nestleItem, daliaItem));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of(nestleAlias, daliaAlias));
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(List.of());
        when(productRepository.findByEan(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            var toSave = invocation.getArgument(0, Product.class);
            if (toSave.getId() == null) toSave.setId(UUID.randomUUID());
            return toSave;
        });

        service.splitVariants(500, false);

        // Only the Dália alias (non-anchor signature) is reassigned.
        var movedIds = ArgumentCaptor.forClass(Collection.class);
        verify(aliasRepository).repointAliasesTo(movedIds.capture(), any(Product.class));
        assertEquals(List.of(daliaAlias.getId()), List.copyOf(movedIds.getValue()));
    }
}
