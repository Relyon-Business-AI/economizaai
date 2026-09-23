package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.AddReceiptItemRequest;
import com.relyon.economizaai.dto.request.ConfirmReceiptRequest;
import com.relyon.economizaai.dto.request.SubmitReceiptRequest;
import com.relyon.economizaai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizaai.exception.ReceiptItemNotFoundException;
import com.relyon.economizaai.exception.ReceiptNotEditableException;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationRuleService;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.SavingsAttributionService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.service.priceindex.PromoDetector;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sibling coverage suite for {@link ReceiptService} — exercises the methods and
 * branches not covered by {@link ReceiptServiceTest}: submit parse-failure
 * recording, list filters/sort/search, get/toResponse with overrides,
 * confirm exclusions + personal-promo notification + downstream collaborators,
 * addItem, and the updateItem alias / cache-bump side effects.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceCoverageTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";
    // Scanned RS QR (full signed URL) — submit rejects a BARE RS chave (gov.br wall).
    private static final String QR_RS =
            "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + CHAVE_RS + "|2|1|1|hash";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private ReceiptIngestionService receiptIngestionService;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private PriceIndexService priceIndexService;
    @Mock private PromoDetector promoDetector;
    @Mock private MarketLocationService marketLocationService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationRuleService notificationRuleService;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private HouseholdCacheGen householdCacheGen;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private SavingsAttributionService savingsAttributionService;
    @Mock private MerchantSupportGate merchantSupportGate;

    @InjectMocks private ReceiptService receiptService;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(canonicalizationService.previewCategory(any())).thenReturn(Optional.empty());
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Default: PRO (unlimited) so the monthly cap is bypassed in existing tests.
        lenient().when(subscriptionGate.monthlyReceiptLimit(any())).thenReturn(Integer.MAX_VALUE);
        lenient().when(sefazIngestionService.resolveChave(any()))
                .thenAnswer(invocation -> ChaveAcessoParser.extractChave(invocation.getArgument(0)));
    }

    // ---------------------------------------------------------------- submit

    @Test
    void submit_replacesStaleFailedParseRowThenPersistsProcessing() {
        var user = buildUser();
        var stale = persistedReceipt(user, ReceiptStatus.FAILED_PARSE);
        when(receiptRepository.findByHouseholdIdAndChaveAcesso(any(), eq(CHAVE_RS)))
                .thenReturn(Optional.of(stale));
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> {
            var persisted = invocation.<Receipt>getArgument(0);
            persisted.setId(UUID.randomUUID());
            return persisted;
        });

        var response = receiptService.submit(user, new SubmitReceiptRequest(QR_RS));

        var deletedCaptor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).delete(deletedCaptor.capture());
        assertEquals(stale.getId(), deletedCaptor.getValue().getId());
        verify(receiptRepository).flush();
        assertEquals(ReceiptStatus.PROCESSING, response.status());
        verify(receiptIngestionService).ingest(eq(response.id()), eq(QR_RS));
    }

    // ------------------------------------------------------------------ list

    @Test
    void list_appliesDefaultIssuedAtDescSortWhenUnsorted() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        receipt.setMarketName("Mercado X");
        Page<Receipt> page = new PageImpl<>(List.of(receipt));
        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(receiptRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(page);

        var result = receiptService.list(user, null, null, null, null, null, null,
                MarketScope.ALL, PageRequest.of(0, 20));

        assertEquals(1, result.getContent().size());
        assertEquals("Mercado X", result.getContent().get(0).marketName());
        var usedSort = pageableCaptor.getValue().getSort().getOrderFor("issuedAt");
        assertNotNull(usedSort);
        assertEquals(Sort.Direction.DESC, usedSort.getDirection());
    }

    @Test
    void list_preservesCallerSortAndTrimsFilters() {
        var user = buildUser();
        Page<Receipt> emptyPage = new PageImpl<>(List.of());
        var callerPageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "totalAmount"));
        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(receiptRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(emptyPage);

        var result = receiptService.list(user,
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2026, Month.DECEMBER, 31, 0, 0),
                "  12345678000190  ",
                List.of(ProductCategory.GROCERIES),
                null,
                "  arroz  ",
                MarketScope.ALL,
                callerPageable);

        assertTrue(result.getContent().isEmpty());
        // Caller-provided sort must be respected unchanged.
        assertSame(callerPageable, pageableCaptor.getValue());
    }

    @Test
    void list_blankFiltersCollapseToNull() {
        var user = buildUser();
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        // Blank cnpj/search should not blow up — they're trimmed to null.
        var result = receiptService.list(user, null, null, "   ", null, null, "   ",
                MarketScope.ALL, PageRequest.of(0, 10));

        assertTrue(result.getContent().isEmpty());
        verify(receiptRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ------------------------------------------------------------------- get

    @Test
    void get_appliesHouseholdOverridesToResponse() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ TIO J 5KG")
                .category(ProductCategory.OTHER).build();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        receipt.getItems().get(0).setProduct(product);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));
        when(categoryOverrideService.overridesByProduct(eq(user.getHousehold().getId()), anyList()))
                .thenReturn(Map.of(product.getId(), "GROCERIES"));

        var response = receiptService.get(user, receipt.getId());

        assertEquals("GROCERIES", response.items().get(0).category());
        assertEquals(ProductCategory.OTHER, product.getCategory(), "global product untouched");
    }

    @Test
    void get_throwsWhenReceiptMissing() {
        var user = buildUser();
        var missingId = UUID.randomUUID();
        when(receiptRepository.findByIdWithItemsAndProducts(missingId)).thenReturn(Optional.empty());

        assertThrows(ReceiptNotFoundException.class, () -> receiptService.get(user, missingId));
    }

    @Test
    void toResponse_skipsOverrideLookupKeysForUnlinkedItems() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED); // item has no product
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));
        var productIdsCaptor = ArgumentCaptor.forClass(List.class);
        when(categoryOverrideService.overridesByProduct(eq(user.getHousehold().getId()),
                productIdsCaptor.capture())).thenReturn(Map.of());

        var response = receiptService.get(user, receipt.getId());

        assertNotNull(response);
        assertTrue(productIdsCaptor.getValue().isEmpty(),
                "no product ids passed when items are unlinked");
    }

    // ---------------------------------------------------------- updateItemCategory

    @Test
    void updateItemCategory_throwsWhenItemNotOnReceipt() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var receiptId = receipt.getId();
        var missingItemId = UUID.randomUUID();
        assertThrows(ReceiptItemNotFoundException.class,
                () -> receiptService.updateItemCategory(user, receiptId, missingItemId, ProductCategory.GROCERIES));
        verify(categoryOverrideService, never()).setOverride(any(), any(), any());
    }

    // --------------------------------------------------------------- confirm

    @Test
    void confirm_appliesExclusionsAndRunsFullDownstreamPipeline() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        // Add a second item so we can exclude one and keep one.
        var keptItem = receipt.getItems().get(0);
        var excludedItem = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(2)
                .rawDescription("FEIJAO 1KG")
                .quantity(new BigDecimal("1"))
                .unit("UN")
                .unitPrice(new BigDecimal("9.90"))
                .totalPrice(new BigDecimal("9.90"))
                .build();
        receipt.addItem(excludedItem);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());

        var request = new ConfirmReceiptRequest(List.of(excludedItem.getId()));
        var response = receiptService.confirm(user, receipt.getId(), request);

        assertEquals(ReceiptStatus.CONFIRMED, response.receipt().status());
        assertTrue(excludedItem.isExcluded(), "requested item excluded");
        assertFalse(keptItem.isExcluded(), "other item kept");
        // Downstream collaborators run after exclusions are applied.
        verify(canonicalizationService).canonicalize(receipt);
        verify(priceIndexService).recordContributions(receipt);
        verify(marketLocationService).registerMarketFromReceipt(receipt);
        verify(householdCacheGen).bump(receipt.getHousehold().getId());
        verifyNoInteractions(notificationService);
    }

    @Test
    void confirm_notifiesUserForEachPersonalPromo() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        receipt.setMarketName("Mercado X");
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        var productId = UUID.randomUUID();
        var promo = new PromoDetector.PersonalPromo(
                UUID.randomUUID(), productId, "ARROZ TIO J 5KG",
                new BigDecimal("19.90"), new BigDecimal("28.90"),
                new BigDecimal("31.14"), 4);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of(promo));
        lenient().when(notificationRuleService.isEnabled(any(), eq(NotificationType.PROMO_PERSONAL))).thenReturn(true);

        var response = receiptService.confirm(user, receipt.getId(), new ConfirmReceiptRequest(null));

        assertEquals(1, response.personalPromos().size());
        var payloadCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(payloadCaptor.capture());
        var payload = payloadCaptor.getValue();
        assertEquals(NotificationType.PROMO_PERSONAL, payload.type());
        assertEquals(user, payload.user());
        assertEquals(receipt.getId().toString(), payload.extras().get("receiptId"));
        assertEquals(productId.toString(), payload.extras().get("productId"));
    }

    @Test
    void confirm_withNullExcludedIdsKeepsAllItems() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));
        when(receiptRepository.save(receipt)).thenReturn(receipt);
        when(promoDetector.detectPersonalPromos(receipt)).thenReturn(List.of());

        receiptService.confirm(user, receipt.getId(), new ConfirmReceiptRequest(null));

        assertFalse(receipt.getItems().get(0).isExcluded());
    }

    // ---------------------------------------------------------------- addItem

    @Test
    void addItem_appendsLineWithNextLineNumber() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION); // existing line 1
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var request = new AddReceiptItemRequest(
                "FEIJAO PRETO 1KG", "7890000000001",
                new BigDecimal("2"), "UN",
                new BigDecimal("8.50"), new BigDecimal("17.00"),
                "Feijão Preto 1kg");

        var response = receiptService.addItem(user, receipt.getId(), request);

        assertEquals(2, response.items().size());
        var savedCaptor = ArgumentCaptor.forClass(ReceiptItem.class);
        verify(receiptItemRepository).save(savedCaptor.capture());
        var added = savedCaptor.getValue();
        assertEquals(2, added.getLineNumber(), "line number appended after existing max");
        assertEquals("FEIJAO PRETO 1KG", added.getRawDescription());
        assertEquals("Feijão Preto 1kg", added.getFriendlyDescription());
        verify(householdCacheGen).bump(receipt.getHousehold().getId());
    }

    @Test
    void addItem_startsAtLineOneWhenReceiptHasNoLineNumbers() {
        var user = buildUser();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(CHAVE_RS)
                .uf(UnidadeFederativa.RS)
                .qrPayload(CHAVE_RS)
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .build();
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var request = new AddReceiptItemRequest(
                "OVOS DZ", null, new BigDecimal("1"), "DZ",
                new BigDecimal("12.00"), new BigDecimal("12.00"), null);

        receiptService.addItem(user, receipt.getId(), request);

        var savedCaptor = ArgumentCaptor.forClass(ReceiptItem.class);
        verify(receiptItemRepository).save(savedCaptor.capture());
        assertEquals(1, savedCaptor.getValue().getLineNumber());
    }

    @Test
    void addItem_throwsWhenReceiptNotPending() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.CONFIRMED);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var request = new AddReceiptItemRequest(
                "OVOS DZ", null, new BigDecimal("1"), "DZ",
                new BigDecimal("12.00"), new BigDecimal("12.00"), null);
        var receiptId = receipt.getId();

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.addItem(user, receiptId, request));
        verify(receiptItemRepository, never()).save(any());
    }

    // --------------------------------------------------------------- updateItem

    @Test
    void updateItem_remembersAliasAndBumpsCache() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.PENDING_CONFIRMATION);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                "ignored", "7891234567890",
                new BigDecimal("1"), "UN",
                new BigDecimal("28.90"), new BigDecimal("28.90"),
                Boolean.TRUE, "Arroz Tio João", null, null);

        receiptService.updateItem(user, receipt.getId(), item.getId(), request);

        assertTrue(item.isExcluded(), "excluded flag applied from request");
        verify(receiptItemRepository).save(item);
        verify(householdProductAliasService).rememberFromItem(receipt.getHousehold(), item);
        verify(householdCacheGen).bump(receipt.getHousehold().getId());
    }

    @Test
    void updateItem_throwsWhenReceiptNotPending() {
        var user = buildUser();
        var receipt = persistedReceipt(user, ReceiptStatus.REJECTED);
        var item = receipt.getItems().get(0);
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId()))
                .thenReturn(Optional.of(receipt));

        var request = new UpdateReceiptItemRequest(
                "x", null, new BigDecimal("1"), null, null, new BigDecimal("1"), null, null, null, null);
        var receiptId = receipt.getId();
        var itemId = item.getId();

        assertThrows(ReceiptNotEditableException.class,
                () -> receiptService.updateItem(user, receiptId, itemId, request));
    }

    // ---------------------------------------------------------------- fixtures

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
}
