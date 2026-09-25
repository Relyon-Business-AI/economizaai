package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.PrefetchedReceiptRequest;
import com.relyon.economizaai.dto.request.SubmitReceiptRequest;
import com.relyon.economizaai.dto.request.UpdateItemPersonalRequest;
import com.relyon.economizaai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizaai.exception.InvalidItemPriceException;
import com.relyon.economizaai.exception.ManualChaveUnsupportedException;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.exception.PrefetchedUnsupportedException;
import com.relyon.economizaai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizaai.exception.ReceiptItemNotFoundException;
import com.relyon.economizaai.exception.ReceiptNotEditableException;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.exception.UnsupportedMerchantException;
import com.relyon.economizaai.exception.UnsupportedStateException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.dto.response.ReceiptItemResponse;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.notifications.SavingsAttributionService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.service.priceindex.PromoDetector;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.PrefetchPolicy;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.sefaz.StateCoverageService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
    // A scanned RS QR (full signed URL). Submit's manual-chave guard rejects a
    // BARE RS chave (gov.br wall), so the happy-path submit tests use the URL a
    // real scan produces; it still resolves to CHAVE_RS server-side.
    private static final String QR_RS =
            "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1|1|hash";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private StateCoverageService stateCoverageService;
    @Mock private ReceiptIngestionService receiptIngestionService;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private PriceIndexService priceIndexService;
    @Mock private PromoDetector promoDetector;
    @Mock private MarketLocationService marketLocationService;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private HouseholdCacheGen householdCacheGen;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private SavingsAttributionService savingsAttributionService;
    @Mock private LocalizedMessageService localizedMessageService;
    @Mock private MerchantSupportGate merchantSupportGate;
    @Mock private PrefetchPolicy prefetchPolicy;

    @InjectMocks private ReceiptService receiptService;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Default: PRO (unlimited) so the monthly cap is bypassed in existing tests.
        lenient().when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(Integer.MAX_VALUE);
        lenient().when(sefazIngestionService.resolveChave(any()))
                .thenAnswer(invocation -> ChaveAcessoParser.extractChave(invocation.getArgument(0)));
        lenient().when(canonicalizationService.previewCategory(any())).thenReturn(Optional.empty());
    }

    @Test
    void get_pendingReceipt_fillsUnlinkedItemCategoriesWithBestEffortPreview() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION); // item has no product
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(canonicalizationService.previewCategory(any())).thenReturn(Optional.of(ProductCategory.GROCERIES));

        var response = receiptService.get(user, receipt.getId());

        assertEquals("GROCERIES", response.items().get(0).category(),
                "review screen should preview the confirm-time category");
        assertNull(response.items().get(0).productId(), "item stays unlinked until confirm");
    }

    @Test
    void get_confirmedReceipt_doesNotSuggestForUnlinkedItems() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var response = receiptService.get(user, receipt.getId());

        assertNull(response.items().get(0).category());
        verify(canonicalizationService, never()).previewCategory(any());
    }

    @Test
    void updateItemCategory_unlinkedItem_linksOrCreatesProductThenOverrides() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED); // item has no product
        var item = receipt.getItems().get(0);
        var created = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J 5KG").build();
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        // Canonicalizer creates/links the product for the unrecognized item.
        when(canonicalizationService.linkOrCreateProduct(receipt, item)).thenReturn(created);

        receiptService.updateItemCategory(user, receipt.getId(), item.getId(), ProductCategory.GROCERIES);

        verify(canonicalizationService).linkOrCreateProduct(receipt, item);
        verify(categoryOverrideService).setOverride(user, created, ProductCategory.GROCERIES);
    }

    @Test
    void updateItemCategory_recordsHouseholdOverrideAndAppliesIt() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J 5KG")
                .category(ProductCategory.OTHER).build();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        var item = receipt.getItems().get(0);
        item.setProduct(product);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(categoryOverrideService.overridesByProduct(eq(user.getHousehold().getId()), any()))
                .thenReturn(Map.of(product.getId(), "GROCERIES"));

        var response = receiptService.updateItemCategory(user, receipt.getId(), item.getId(), ProductCategory.GROCERIES);

        verify(categoryOverrideService).setOverride(user, product, ProductCategory.GROCERIES);
        assertEquals("GROCERIES", response.items().get(0).category(), "household override shown, global stays OTHER");
        assertEquals(ProductCategory.OTHER, product.getCategory(), "global product untouched");
    }

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private ParsedReceipt sampleParsed() {
        return ParsedReceipt.builder()
                .chaveAcesso(CHAVE_RS)
                .cnpjEmitente("12345678000190")
                .marketName("Mercado X")
                .marketAddress("Rua Y, 123")
                .issuedAt(LocalDateTime.of(2026, Month.APRIL, 15, 18, 0))
                .totalAmount(new BigDecimal("57.80"))
                .sourceUrl(null)
                .rawHtml("<html/>")
                .items(List.of(ParsedReceiptItem.builder()
                        .lineNumber(1)
                        .rawDescription("ARROZ TIO J 5KG")
                        .ean("7891234567890")
                        .quantity(new BigDecimal("2"))
                        .unit("UN")
                        .unitPrice(new BigDecimal("28.90"))
                        .totalPrice(new BigDecimal("57.80"))
                        .build()))
                .build();
    }

    private Receipt persistedReceipt(User user, ReceiptStatus status) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(CHAVE_RS)
                .uf(UnidadeFederativa.RS)
                .qrPayload(CHAVE_RS)
                .status(status)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription("ARROZ TIO J 5KG")
                .quantity(new BigDecimal("2"))
                .unit("UN")
                .unitPrice(new BigDecimal("28.90"))
                .totalPrice(new BigDecimal("57.80"))
                .build());
        return receipt;
    }

    @Test
    void submit_persistsProcessingAndDispatchesIngestion() {
        var user = buildUser();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(QR_RS));

        // submit returns immediately as PROCESSING (no SEFAZ work on the request thread)
        assertNotNull(response.id());
        assertEquals(ReceiptStatus.PROCESSING, response.status());
        verify(sefazIngestionService, never()).fetch(any());
        // the slow ingestion is handed off to the background service
        verify(receiptIngestionService).ingest(eq(response.id()), eq(QR_RS));
    }

    @Test
    void submitPrefetched_rejectedUfNeverPersistsAnything() {
        var user = buildUser();
        doThrow(new PrefetchedUnsupportedException("RS"))
                .when(prefetchPolicy).requireAllowed(UnidadeFederativa.RS);

        assertThrows(PrefetchedUnsupportedException.class,
                () -> receiptService.submitPrefetched(user, new PrefetchedReceiptRequest(QR_RS, "<html>content</html>")));

        verify(receiptRepository, never()).save(any());
        verify(receiptIngestionService, never()).ingestPrefetched(any(), any(), any());
    }

    @Test
    void submitPrefetched_allowedUfPersistsProcessingAndDispatchesIngestion() {
        var user = buildUser();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var receipt = inv.<Receipt>getArgument(0);
            receipt.setId(UUID.randomUUID());
            return receipt;
        });

        var response = receiptService.submitPrefetched(user, new PrefetchedReceiptRequest(QR_RS, "<html>content</html>"));

        assertEquals(ReceiptStatus.PROCESSING, response.status());
        verify(prefetchPolicy).requireAllowed(UnidadeFederativa.RS);
        verify(receiptIngestionService).ingestPrefetched(eq(response.id()), eq(QR_RS), eq("<html>content</html>"));
    }

    @Test
    void submitPrefetched_dispatchRejectionMarksRowFailed() {
        // Untransacted path (no active tx sync) → the dispatch runs inline. A pool rejection must
        // fail the already-committed PROCESSING row, not propagate and leave the FE polling forever.
        var user = buildUser();
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var receipt = inv.<Receipt>getArgument(0);
            receipt.setId(UUID.randomUUID());
            return receipt;
        });
        doThrow(new RuntimeException("pool rejected"))
                .when(receiptIngestionService).ingestPrefetched(any(), any(), any());

        var response = receiptService.submitPrefetched(user, new PrefetchedReceiptRequest(QR_RS, "<html>content</html>"));

        assertEquals(ReceiptStatus.PROCESSING, response.status());
        verify(receiptIngestionService).markFailed(eq(response.id()), any(RuntimeException.class));
    }

    @Test
    void submit_rejectsKnownBlockedMerchantWithoutStoringAnything() {
        var user = buildUser();
        when(merchantSupportGate.isKnownBlockedCnpj("12345678000190")).thenReturn(true);

        assertThrows(UnsupportedMerchantException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(QR_RS)));

        verify(receiptRepository, never()).save(any());
        verify(receiptIngestionService, never()).ingest(any(), any());
    }

    @Test
    void submit_experimentalStateAtEvidenceCap_rejectsWithoutStoringOrSpending() {
        var user = buildUser();
        // Experimental UF that already gave us enough failing samples: stop attempting/spending,
        // fail fast (option B cap). Under the cap it would proceed (covered by the happy-path tests).
        when(sefazIngestionService.isExperimental(any())).thenReturn(true);
        when(stateCoverageService.hasEnoughEvidence(any())).thenReturn(true);

        assertThrows(UnsupportedStateException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(QR_RS)));

        verify(receiptRepository, never()).save(any());
        verify(receiptIngestionService, never()).ingest(any(), any());
    }

    @Test
    void submit_rejectsBareRsChaveWithLocalizedError() {
        var user = buildUser();

        // A manually-typed bare RS chave can't be looked up (gov.br wall) — reject
        // up front, before spending any async work or paid fallback.
        assertThrows(ManualChaveUnsupportedException.class,
                () -> receiptService.submit(user, new SubmitReceiptRequest(CHAVE_RS)));

        verify(receiptRepository, never()).save(any());
        verify(receiptIngestionService, never()).ingest(any(), any());
    }

    @Test
    void submit_throwsPaywallWhenFreeUserAtMonthlyCap() {
        var user = buildUser();
        when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(5);
        when(receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(eq(user.getId()), any()))
                .thenReturn(5L);

        var request = new SubmitReceiptRequest(QR_RS);
        assertThrows(PaywallException.class,
                () -> receiptService.submit(user, request));

        verify(receiptIngestionService, never()).ingest(any(), any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void submit_allowsWhenFreeUserUnderMonthlyCap() {
        var user = buildUser();
        when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(5);
        when(receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(eq(user.getId()), any()))
                .thenReturn(2L);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS))).thenReturn(Optional.empty());
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(QR_RS));

        assertEquals(ReceiptStatus.PROCESSING, response.status());
        verify(receiptIngestionService).ingest(eq(response.id()), eq(QR_RS));
    }

    @Test
    void submit_rejectsDuplicateOnlyWhenPriorIsConfirmed() {
        var user = buildUser();
        var existingConfirmed = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS)))
                .thenReturn(Optional.of(existingConfirmed));

        var request = new SubmitReceiptRequest(QR_RS);
        assertThrows(ReceiptAlreadyIngestedException.class,
                () -> receiptService.submit(user, request));

        verify(receiptRepository, never()).save(any());
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void submit_replacesPriorPendingReceiptInsteadOfBlocking() {
        // FE-reported bug: user closed the app mid-review, then can't re-scan.
        // Non-CONFIRMED prior rows must be deleted to make room for the fresh submission.
        var user = buildUser();
        var stale = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS)))
                .thenReturn(Optional.of(stale));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> {
            var r = inv.<Receipt>getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(QR_RS));

        // delete(T) and delete(DeleteSpecification<T>) overload conflict — use
        // an explicitly-typed captor to disambiguate, then check identity.
        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).delete(captor.capture());
        assertEquals(stale.getId(), captor.getValue().getId());
        assertEquals(ReceiptStatus.PROCESSING, response.status());
    }

    @Test
    void delete_callsRepositoryDeleteForOwnedReceipt() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        receiptService.delete(user, receipt.getId());

        var captor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).delete(captor.capture());
        assertEquals(receipt.getId(), captor.getValue().getId());
    }

    @Test
    void delete_throwsWhenReceiptBelongsToAnotherHousehold() {
        var user = buildUser();
        var stranger = buildUser();
        var receipt = persistedReceipt(stranger, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        var receiptId = receipt.getId();

        assertThrows(ReceiptNotFoundException.class, () -> receiptService.delete(user, receiptId));
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void delete_throwsWhenReceiptDoesNotExist() {
        var user = buildUser();
        var missingId = UUID.randomUUID();
        when(receiptRepository.findByIdWithItemsAndProducts(missingId)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> receiptService.delete(user, missingId));
        verify(receiptRepository, never()).delete(any(Receipt.class));
    }

    @Test
    void confirm_marksReceiptConfirmedAndStampsTime() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());

        var response = receiptService.confirm(user, receipt.getId(), null);

        assertEquals(ReceiptStatus.CONFIRMED, response.receipt().status());
        assertNotNull(response.receipt().confirmedAt());
        assertEquals(0, response.personalPromos().size());
    }

    @Test
    void confirmStale_autoConfirmsPendingReceipt() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());

        receiptService.confirmStale(receipt.getId());

        assertEquals(ReceiptStatus.CONFIRMED, receipt.getStatus());
        assertNotNull(receipt.getConfirmedAt());
    }

    @Test
    void listImportStaging_returnsNonConfirmedImportReceipts() {
        var user = buildUser();
        var staging = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByHouseholdIdAndOriginAndStatusNotOrderByCreatedAtDesc(
                user.getHousehold().getId(), ReceiptOrigin.IMPORT, ReceiptStatus.CONFIRMED))
                .thenReturn(List.of(staging));

        var result = receiptService.listImportStaging(user);

        assertEquals(1, result.size());
        assertEquals(staging.getId(), result.get(0).id());
    }

    @Test
    void confirmBatch_confirmsEachOwnedReceiptAndCountsSuccesses() {
        var user = buildUser();
        var one = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var two = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(one.getId())).thenReturn(Optional.of(one));
        when(receiptRepository.findByIdWithItemsAndProducts(two.getId())).thenReturn(Optional.of(two));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promoDetector.detectPersonalPromos(any(Receipt.class))).thenReturn(List.of());

        var confirmed = receiptService.confirmBatch(user, List.of(one.getId(), two.getId()));

        assertEquals(2, confirmed);
        assertEquals(ReceiptStatus.CONFIRMED, one.getStatus());
        assertEquals(ReceiptStatus.CONFIRMED, two.getStatus());
    }

    @Test
    void confirmStale_skipsWhenNoLongerPending() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        receiptService.confirmStale(receipt.getId());

        verify(receiptRepository, never()).save(any());
    }

    @Test
    void confirm_snapshotsCategoryFromLinkedProduct() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("ARROZ TIO J 5KG").category(ProductCategory.GROCERIES).build();
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());
        // canonicalize() links the item during confirm — simulate that side effect.
        when(canonicalizationService.canonicalize(receipt)).thenAnswer(invocation -> {
            item.setProduct(product);
            return null;
        });

        receiptService.confirm(user, receipt.getId(), null);

        assertEquals(ProductCategory.GROCERIES, item.getCategoryAtConfirmation(),
                "category must be frozen at confirmation time");
    }

    @Test
    void itemResponse_prefersSnapshotOverLiveProductCategory() {
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("ARROZ").category(ProductCategory.OTHER).build(); // live category drifted
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID()).lineNumber(1).rawDescription("ARROZ TIO J 5KG")
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("28.90"))
                .product(product)
                .categoryAtConfirmation(ProductCategory.GROCERIES) // what the user saw
                .build();

        var response = ReceiptItemResponse.from(item);

        assertEquals("GROCERIES", response.category(),
                "confirmed history shows the snapshot, not the drifted live category");
    }

    @Test
    void confirm_succeedsEvenWhenSavingsAttributionThrows() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(savingsAttributionService).attribute(receipt);

        var response = receiptService.confirm(user, receipt.getId(), null);

        // Attribution is best-effort analytics — its failure must never break confirm.
        assertEquals(ReceiptStatus.CONFIRMED, response.receipt().status());
        verify(savingsAttributionService).attribute(receipt);
    }

    @Test
    void confirm_throwsForOtherHouseholdReceipt() {
        var user = buildUser();
        var stranger = buildUser();
        var receipt = persistedReceipt(stranger, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        var receiptId = receipt.getId();

        assertThrows(ReceiptNotFoundException.class,
                () -> receiptService.confirm(user, receiptId, null));
    }

    @Test
    void confirm_throwsWhenAlreadyConfirmed() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        var receiptId = receipt.getId();

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.confirm(user, receiptId, null));
    }

    @Test
    void updateItem_modifiesItemFields() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                "IGNORED — rawDescription is immutable",
                "7891234567890",
                new BigDecimal("3"),
                "UN",
                new BigDecimal("28.90"),
                new BigDecimal("86.70"),
                null,
                "Arroz Tio João 5kg",
                null,
                null
        );

        var response = receiptService.updateItem(user, receipt.getId(), item.getId(), request);

        var updated = response.items().get(0);
        // rawDescription stays as the original (immutable audit text)
        assertEquals("ARROZ TIO J 5KG", updated.rawDescription());
        // friendlyDescription captures the user's display rename
        assertEquals("Arroz Tio João 5kg", updated.friendlyDescription());
        // displayDescription is the resolved field — friendly when set, raw otherwise
        assertEquals("Arroz Tio João 5kg", updated.displayDescription());
        assertEquals(new BigDecimal("3"), updated.quantity());
        assertEquals(new BigDecimal("86.70"), updated.totalPrice());
    }

    @Test
    void updateItem_recordsPaidPriceAndDerivesUnitForDiscountedLine() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        // Original line: qty 2 × 10.00 = 20.00 as-printed; user paid 15.00 on promo.
        var request = new UpdateReceiptItemRequest(
                null, item.getEan(), new BigDecimal("2"), item.getUnit(),
                new BigDecimal("10.00"), new BigDecimal("20.00"),
                null, null, null, new BigDecimal("15.00"));

        var response = receiptService.updateItem(user, receipt.getId(), item.getId(), request);

        var updated = response.items().get(0);
        assertEquals(new BigDecimal("20.00"), updated.totalPrice()); // original stays as-printed
        assertEquals(0, updated.paidTotalPrice().compareTo(new BigDecimal("15.00")));
        assertEquals(0, updated.paidUnitPrice().compareTo(new BigDecimal("7.5000"))); // 15.00 ÷ 2
        assertTrue(updated.promotional());
    }

    @Test
    void updateItem_paidPriceAboveOriginal_throws() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                null, item.getEan(), new BigDecimal("1"), item.getUnit(),
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                null, null, null, new BigDecimal("12.00"));
        var receiptId = receipt.getId();
        var itemId = item.getId();

        assertThrows(InvalidItemPriceException.class,
                () -> receiptService.updateItem(user, receiptId, itemId, request));
    }

    @Test
    void updateItem_throwsForUnknownItem() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest("X", null,
                new BigDecimal("1"), null, null, new BigDecimal("1"), null, null, null, null);
        var receiptId = receipt.getId();
        var missingItemId = UUID.randomUUID();

        assertThrows(ReceiptItemNotFoundException.class,
                () -> receiptService.updateItem(user, receiptId, missingItemId, request));
    }

    @Test
    void updatePersonalItem_marksNotMine_onConfirmedReceipt_dropsFromHouseholdTotal() {
        var user = buildUser();
        // CONFIRMED — proves the personal layer is editable after confirmation (no requirePending).
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateItemPersonalRequest(true, null, null, null);
        var response = receiptService.updatePersonalItem(user, receipt.getId(), item.getId(), request);

        assertTrue(response.items().get(0).excludedFromPersonal());
        assertTrue(item.isExcludedFromPersonal());
        // Sole item is now "not mine" → household total is zero, but the item is NOT `excluded`
        // (it still feeds the collaborative index).
        assertEquals(0, response.householdTotalAmount().compareTo(BigDecimal.ZERO));
        assertFalse(item.isExcluded());
    }

    @Test
    void updatePersonalItem_editsDescriptionAndPaidPrice_onConfirmedReceipt() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        var item = receipt.getItems().get(0); // qty 2 × 28.90 = 57.80 as-printed
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateItemPersonalRequest(null, "Arroz Tio João 5kg", null, new BigDecimal("50.00"));
        var response = receiptService.updatePersonalItem(user, receipt.getId(), item.getId(), request);

        var updated = response.items().get(0);
        assertEquals("Arroz Tio João 5kg", updated.friendlyDescription());
        assertEquals(new BigDecimal("57.80"), updated.totalPrice()); // printed price untouched
        assertEquals(0, updated.paidTotalPrice().compareTo(new BigDecimal("50.00")));
        assertTrue(updated.promotional());
    }

    @Test
    void updatePersonalItem_throwsForUnknownItem() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var request = new UpdateItemPersonalRequest(true, null, null, null);
        var receiptId = receipt.getId();
        var missingItemId = UUID.randomUUID();

        assertThrows(ReceiptItemNotFoundException.class,
                () -> receiptService.updatePersonalItem(user, receiptId, missingItemId, request));
    }

    @Test
    void reject_marksReceiptRejected() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);

        var response = receiptService.reject(user, receipt.getId());

        assertEquals(ReceiptStatus.REJECTED, response.status());
    }
}
