package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AI-assisted variant split with a MOCKED {@link AiGateway}.
 * The AI service reuses the REAL {@link ProductVariantSplitService} apply mechanics,
 * so that one is constructed with mocked repositories + a mocked {@link BrandExtractor}.
 */
@ExtendWith(MockitoExtension.class)
class AiProductVariantSplitServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private BrandExtractor brandExtractor;
    @Mock private AiGateway aiGateway;
    @Mock private TransactionTemplate transactionTemplate;

    private ProductVariantSplitService deterministicSplit;
    private AiProductVariantSplitService service;

    private static final String MELITTA_EAN = "7891020302011";
    private static final String BOM_JESUS_EAN = "7896397200014";
    private static final String PIMPINELA_EAN = "7898903470012";
    private static final String TANG_UVA_EAN = "7622300807412";
    private static final String TANG_LARANJA_EAN = "7622300807429";
    private static final String COCA_ZERO_EAN = "7894900701609";

    @BeforeEach
    void setUp() {
        deterministicSplit = new ProductVariantSplitService(productRepository, receiptItemRepository,
                priceObservationRepository, aliasRepository, brandExtractor);
        service = new AiProductVariantSplitService(deterministicSplit, productRepository, aiGateway,
                transactionTemplate);
        lenient().when(aiGateway.isEnabled()).thenReturn(true);
        lenient().when(aiGateway.curatorModel()).thenReturn("claude-opus-4-8");
        // Run the apply transaction inline.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    private Product coffeeProduct() {
        return Product.builder()
                .id(UUID.randomUUID())
                .ean(MELITTA_EAN)
                .normalizedName("CAFE MELITTA 500G")
                .genericName("Café")
                .genericNameNorm("cafe")
                .category(ProductCategory.GROCERIES)
                .build();
    }

    private ReceiptItem item(Product product, String rawDescription, String ean, String unitPrice) {
        var receipt = Receipt.builder().id(UUID.randomUUID()).build();
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

    private void stubSave() {
        when(productRepository.findByEan(any())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            var toSave = invocation.getArgument(0, Product.class);
            if (toSave.getId() == null) toSave.setId(UUID.randomUUID());
            return toSave;
        });
    }

    @Test
    void aiClustersThreeCoffeeBrands_splitsIntoThree() {
        // The deterministic dictionary returns "café" for all three (no brand), so they
        // collapse to ONE signature — still mixed. The AI separates them into 3 products.
        var product = coffeeProduct();
        lenient().when(brandExtractor.find(any())).thenReturn(null); // extractor can't tell them apart
        var melitta = item(product, "CAFE MELITTA 500G", MELITTA_EAN, "12.90");
        var bomJesus = item(product, "CAFE BOM JESUS 500G", BOM_JESUS_EAN, "9.90");
        var pimpinela = item(product, "CAFE PIMPINELA 500G", PIMPINELA_EAN, "8.90");
        var items = List.of(melitta, bomJesus, pimpinela);

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any())).thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId())).thenReturn(items);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(List.of());
        stubSave();
        // AI clusters the three items into three distinct products (ids are item:0/1/2), HIGH
        // confidence. Names are materially different (Melitta / Bom Jesus / Pimpinela) → applied.
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\"],[\"item:1\"],[\"item:2\"]],\"confidence\":\"HIGH\"}");

        var response = service.splitVariants(50, false);

        assertTrue(response.aiAvailable());
        assertEquals(1, response.candidates());
        assertEquals(1, response.productsSplit());
        assertEquals(2, response.newProductsCreated(), "three groups → anchor keeps one, two new products");
        assertEquals(3, response.splits().get(0).resultingProducts().size());
        verify(productRepository, org.mockito.Mockito.times(2)).save(any(Product.class));
    }

    @Test
    void sameRealEanInDifferentAiGroups_eanCohesionOverridesAndKeepsThemTogether() {
        // The AI (wrongly) splits two items that share the SAME real GTIN. EAN cohesion
        // forces them back into one group — the shared real GTIN is ground truth.
        var product = Product.builder()
                .id(UUID.randomUUID())
                .ean(COCA_ZERO_EAN)
                .normalizedName("COCA COLA 600ML")
                .genericName("Refrigerante")
                .genericNameNorm("refrigerante")
                .category(ProductCategory.BEVERAGES)
                .build();
        lenient().when(brandExtractor.find(any())).thenReturn("Coca-Cola");
        // Both items share the same real EAN but one carries a different real EAN'd sibling,
        // so the product still enters the gate (>1 distinct real EAN overall).
        var plain = item(product, "COCA COLA S/AC 600ML", COCA_ZERO_EAN, "4.50");
        var zero = item(product, "REFRIGERANTE COCA COLA 600ml ZERO", COCA_ZERO_EAN, "4.50");
        var fanta = item(product, "FANTA LARANJA 600ML", TANG_LARANJA_EAN, "4.50"); // distinct real EAN → gate opens
        var items = List.of(plain, zero, fanta);

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any())).thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId())).thenReturn(items);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.findByProduct(product)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(product.getId())).thenReturn(List.of());
        stubSave();
        // AI puts the two same-EAN cokes in DIFFERENT groups (item:0 vs item:1); fanta item:2 alone.
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\"],[\"item:1\"],[\"item:2\"]],\"confidence\":\"HIGH\"}");

        var response = service.splitVariants(50, false);

        // 2 final groups: {plain+zero merged by EAN cohesion} and {fanta}. Only ONE new product.
        assertEquals(1, response.productsSplit());
        assertEquals(1, response.newProductsCreated());
        assertEquals(2, response.splits().get(0).resultingProducts().size(),
                "same real EAN forced into one group despite the AI splitting it");

        // The two same-EAN cokes are never on opposite sides of a repoint: only the fanta item moves.
        var movedIds = ArgumentCaptor.forClass(Collection.class);
        verify(receiptItemRepository).repointItemsTo(movedIds.capture(), any(Product.class));
        assertEquals(List.of(fanta.getId()), List.copyOf(movedIds.getValue()));
    }

    @Test
    void aiReturnsOneGroup_noSplit() {
        // Deterministic signature count > 1 (two different flavour tokens open the gate),
        // but the AI decides they're all the SAME product → one group → no split.
        var product = Product.builder()
                .id(UUID.randomUUID())
                .ean(TANG_UVA_EAN)
                .normalizedName("TANG UVA 25G")
                .genericName("Refresco")
                .genericNameNorm("refresco")
                .category(ProductCategory.BEVERAGES)
                .build();
        lenient().when(brandExtractor.find(any())).thenReturn("Tang");
        var uva = item(product, "TANG UVA 25G", TANG_UVA_EAN, "1.20");
        var laranja = item(product, "TANG LARANJA 25G", TANG_LARANJA_EAN, "1.20");
        var items = List.of(uva, laranja);

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any())).thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId())).thenReturn(items);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\",\"item:1\"]],\"confidence\":\"HIGH\"}");

        var response = service.splitVariants(50, false);

        assertEquals(0, response.productsSplit(), "AI returned a single group → nothing to split");
        assertEquals(0, response.newProductsCreated());
        assertTrue(response.splits().isEmpty());
        verify(productRepository, never()).save(any(Product.class));
        verify(receiptItemRepository, never()).repointItemsTo(anyList(), any(Product.class));
    }

    @Test
    void candidateGate_onlySelectsStillMixedProducts_andSkipsCleanOnes() {
        // Two pre-filter candidates (both have >1 distinct raw EAN string, so both pass the
        // cheap SQL pre-filter). One is clean once refined: exactly ONE real EAN (the other
        // EAN is a placeholder) AND one signature → NOT still mixed → never sent to the AI.
        // The other IS still mixed (two real EANs, two brands) → sent.
        var clean = Product.builder()
                .id(UUID.randomUUID())
                .ean(TANG_UVA_EAN)
                .normalizedName("AGUA CRYSTAL S GAS 500ML")
                .genericName("Água")
                .genericNameNorm("agua")
                .category(ProductCategory.BEVERAGES)
                .build();
        lenient().when(brandExtractor.find("AGUA CRYSTAL S GAS 500ML")).thenReturn("Crystal");
        var crystalA = item(clean, "AGUA CRYSTAL S GAS 500ML", TANG_UVA_EAN, "1.50");
        var crystalB = item(clean, "AGUA CRYSTAL S GAS 500ML", "00000001124676", "1.50"); // placeholder EAN

        var mixed = coffeeProduct();
        lenient().when(brandExtractor.find("CAFE MELITTA 500G")).thenReturn("Melitta");
        lenient().when(brandExtractor.find("CAFE BOM JESUS 500G")).thenReturn("Bom Jesus");
        var melitta = item(mixed, "CAFE MELITTA 500G", MELITTA_EAN, "12.90");
        var bomJesus = item(mixed, "CAFE BOM JESUS 500G", BOM_JESUS_EAN, "9.90");

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any()))
                .thenReturn(List.of(clean.getId(), mixed.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(clean.getId())).thenReturn(List.of(crystalA, crystalB));
        when(receiptItemRepository.findAllLinkedWithReceipt(mixed.getId())).thenReturn(List.of(melitta, bomJesus));
        when(productRepository.findById(mixed.getId())).thenReturn(Optional.of(mixed));
        when(aliasRepository.findByProduct(mixed)).thenReturn(List.of());
        when(priceObservationRepository.findObservationTraceByProduct(mixed.getId())).thenReturn(List.of());
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\"],[\"item:1\"]],\"confidence\":\"HIGH\"}");

        var response = service.splitVariants(50, true); // dry-run: no mutation, still gated

        // Only the mixed product is a candidate; the clean one was gated out before any AI call.
        assertEquals(1, response.candidates(), "clean single-signature product is gated out");
        assertTrue(response.dryRun());
        // The AI was consulted exactly once (for the mixed product only).
        verify(aiGateway, org.mockito.Mockito.times(1)).complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt());
        // dry-run mutates nothing.
        verify(productRepository, never()).save(any(Product.class));
        // dry-run reports the mixed product's split as SAFE-to-apply (HIGH conf + distinct names).
        assertEquals(1, response.safety().size());
        assertTrue(response.safety().get(0).safeToApply());
        assertEquals("SAFE", response.safety().get(0).reason());
    }

    @Test
    void identicalNamedItemsInDifferentAiGroups_nameCohesionMergesThemBack_noSplit() {
        // Three items with the IDENTICAL raw name but three DIFFERENT real EANs (re-barcode).
        // The gate opens (>1 distinct real EAN). The AI over-splits them into three groups, but
        // the identical-name cohesion guard forces them back into one → no split.
        var product = Product.builder()
                .id(UUID.randomUUID())
                .ean(MELITTA_EAN)
                .normalizedName("SUCO NATURAL ONE 900ML")
                .genericName("Suco")
                .genericNameNorm("suco")
                .category(ProductCategory.BEVERAGES)
                .build();
        lenient().when(brandExtractor.find(any())).thenReturn("One");
        var oneA = item(product, "SUCO NATURAL ONE 900ML", MELITTA_EAN, "9.90");
        var oneB = item(product, "SUCO NATURAL ONE 900ML", BOM_JESUS_EAN, "9.90");
        var oneC = item(product, "SUCO NATURAL ONE 900ML", PIMPINELA_EAN, "9.90");
        var items = List.of(oneA, oneB, oneC);

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any())).thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId())).thenReturn(items);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // AI (wrongly) splits the three identical-named items into three groups, HIGH confidence.
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\"],[\"item:1\"],[\"item:2\"]],\"confidence\":\"HIGH\"}");

        var response = service.splitVariants(50, false);

        assertEquals(1, response.candidates());
        assertEquals(0, response.productsSplit(), "identical normalized names forced into one group → no split");
        assertEquals(0, response.newProductsCreated());
        assertTrue(response.splits().isEmpty());
        verify(productRepository, never()).save(any(Product.class));
        verify(receiptItemRepository, never()).repointItemsTo(anyList(), any(Product.class));
    }

    @Test
    void lowConfidenceSplit_skippedOnApply_butShownInDryRun() {
        // Two DIFFERENT-named coffees, two real EANs → gate opens. The AI separates them but with
        // LOW confidence. On apply the safe-apply gate DROPS the split (items stay merged); in
        // dry-run the verdict is surfaced as SKIPPED / LOW_CONFIDENCE.
        var product = coffeeProduct();
        lenient().when(brandExtractor.find(any())).thenReturn(null);
        var melitta = item(product, "CAFE MELITTA 500G", MELITTA_EAN, "12.90");
        var bomJesus = item(product, "CAFE BOM JESUS 500G", BOM_JESUS_EAN, "9.90");
        var items = List.of(melitta, bomJesus);

        when(receiptItemRepository.findProductIdsWithMultipleItemEans(any())).thenReturn(List.of(product.getId()));
        when(receiptItemRepository.findAllLinkedWithReceipt(product.getId())).thenReturn(items);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aiGateway.complete(eq(AiActivity.VARIANT_SPLIT), any(), any(), any(), anyInt()))
                .thenReturn("{\"groups\":[[\"item:0\"],[\"item:1\"]],\"confidence\":\"LOW\"}");

        // Apply: the low-confidence split is dropped — nothing mutated, nothing reported as split.
        var applied = service.splitVariants(50, false);
        assertEquals(0, applied.productsSplit(), "low-confidence split is dropped on apply");
        assertEquals(0, applied.newProductsCreated());
        assertTrue(applied.splits().isEmpty());
        verify(productRepository, never()).save(any(Product.class));

        // Dry-run: the same split is surfaced as SKIPPED with reason LOW_CONFIDENCE.
        var preview = service.splitVariants(50, true);
        assertTrue(preview.splits().isEmpty(), "low-confidence split is not a SAFE split");
        assertEquals(1, preview.safety().size());
        assertFalse(preview.safety().get(0).safeToApply());
        assertEquals("LOW_CONFIDENCE", preview.safety().get(0).reason());
        assertEquals("LOW", preview.safety().get(0).confidence());
        assertEquals(2, preview.safety().get(0).proposedGroups());
    }

    @Test
    void aiInert_noKey_isNoOpAndDoesNotCrash() {
        when(aiGateway.isEnabled()).thenReturn(false);

        var response = service.splitVariants(50, true);

        assertFalse(response.aiAvailable());
        assertEquals(0, response.candidates());
        assertEquals(0, response.productsSplit());
        assertTrue(response.splits().isEmpty());
        // No candidate query, no AI call — fully inert.
        verifyNoInteractions(receiptItemRepository, productRepository);
        verify(aiGateway, never()).complete(any(), any(), any(), any(), anyInt());
    }
}
