package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.AddReceiptItemRequest;
import com.relyon.economizaai.dto.request.ConfirmReceiptRequest;
import com.relyon.economizaai.dto.request.DeviceContentRequest;
import com.relyon.economizaai.dto.request.PrefetchedReceiptRequest;
import com.relyon.economizaai.dto.request.SubmitReceiptRequest;
import com.relyon.economizaai.dto.request.UpdateItemPersonalRequest;
import com.relyon.economizaai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizaai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizaai.dto.response.ReceiptResponse;
import com.relyon.economizaai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizaai.exception.ManualChaveUnsupportedException;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizaai.exception.ReceiptItemNotFoundException;
import com.relyon.economizaai.exception.InvalidItemPriceException;
import com.relyon.economizaai.exception.ReceiptNotEditableException;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.exception.UnsupportedMerchantException;
import com.relyon.economizaai.config.MdcContextFilter;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.model.enums.ReceiptStatus;
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
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.priceindex.PromoDetector;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.PrefetchPolicy;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final SefazIngestionService sefazIngestionService;
    private final PrefetchPolicy prefetchPolicy;
    private final CanonicalizationService canonicalizationService;
    private final PriceIndexService priceIndexService;
    private final PromoDetector promoDetector;
    private final MarketLocationService marketLocationService;
    private final MerchantSupportGate merchantSupportGate;
    private final NotificationService notificationService;
    private final NotificationRuleService notificationRuleService;
    private final HouseholdProductAliasService householdProductAliasService;
    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final HouseholdCacheGen householdCacheGen;
    private final LocalizedMessageService localizedMessageService;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;
    private final SavingsAttributionService savingsAttributionService;
    private final ReceiptIngestionService receiptIngestionService;

    /**
     * Ingest a receipt from a scanned QR code. Returns IMMEDIATELY with a
     * PROCESSING receipt: the slow part (SEFAZ fetch + captcha solve + parse,
     * up to a couple of minutes) runs in the background so the FE's HTTP call
     * never times out. The FE polls {@code GET /receipts/{id}} until the status
     * leaves PROCESSING — PENDING_CONFIRMATION (ready to review) or FAILED_PARSE.
     *
     * <p>Validation (monthly cap, per-household uniqueness) still happens
     * synchronously here so the caller gets those errors up front.
     */
    @Transactional
    public ReceiptResponse submit(User user, SubmitReceiptRequest request) {
        var qrPayload = request.qrPayload();
        var receipt = validateAndPersistProcessing(user, qrPayload);
        var receiptId = receipt.getId();
        dispatchAfterCommit(receiptId, () -> receiptIngestionService.ingest(receiptId, qrPayload));
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /**
     * As {@link #submit(User, SubmitReceiptRequest)} but {@code deviceCapable} carries
     * whether the app can resolve a blocked-state receipt on-device (it sent the
     * X-Device-Fetch header). Only such clients may be handed the NEEDS_DEVICE_FETCH
     * status; older apps that don't know it would crash rendering it, so they get
     * FAILED_PARSE instead.
     */
    @Transactional
    public ReceiptResponse submit(User user, SubmitReceiptRequest request, boolean deviceCapable) {
        var qrPayload = request.qrPayload();
        var receipt = validateAndPersistProcessing(user, qrPayload);
        var receiptId = receipt.getId();
        dispatchAfterCommit(receiptId, () -> receiptIngestionService.ingest(receiptId, qrPayload, deviceCapable));
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /**
     * Submit a receipt whose SEFAZ page the CLIENT already fetched on-device — for
     * portals that serve phones but block our datacenter server (e.g. Pernambuco).
     * Same up-front validation as {@link #submit}; the async ingestion parses the
     * provided content instead of scraping from our IP.
     */
    public ReceiptResponse submitPrefetched(User user, PrefetchedReceiptRequest request) {
        var qrPayload = request.qrPayload();
        var rawContent = request.rawContent();
        // Client-authored content is only trusted for UFs the server cannot
        // fetch itself — everywhere else the server fetch is the integrity check.
        prefetchPolicy.requireAllowed(ChaveAcessoParser.extractUf(sefazIngestionService.resolveChave(qrPayload)));
        var receipt = validateAndPersistProcessing(user, qrPayload);
        var receiptId = receipt.getId();
        dispatchAfterCommit(receiptId, () -> receiptIngestionService.ingestPrefetched(receiptId, qrPayload, rawContent));
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /**
     * The app's on-device retry for a receipt the server left in NEEDS_DEVICE_FETCH
     * (state blocks our datacenter IP). The app fetched the nota on its own accepted
     * IP and reposts the body; we flip the row back to PROCESSING and re-ingest with
     * it — reusing the stored qrPayload/chave. If the device content also can't be
     * parsed it becomes a real FAILED_PARSE (no NEEDS_DEVICE_FETCH loop).
     */
    @Transactional
    public ReceiptResponse submitDeviceContent(User user, UUID receiptId, DeviceContentRequest request) {
        var receipt = loadOwned(user, receiptId);
        if (receipt.getStatus() != ReceiptStatus.NEEDS_DEVICE_FETCH) {
            throw new ReceiptNotEditableException(receipt.getStatus().name());
        }
        var qrPayload = receipt.getQrPayload();
        var rawContent = request.rawContent();
        receipt.setStatus(ReceiptStatus.PROCESSING);
        receiptRepository.save(receipt);
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        log.info("device-content received, re-ingesting on-device fetch");
        dispatchAfterCommit(receiptId, () -> receiptIngestionService.ingestPrefetched(receiptId, qrPayload, rawContent));
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /**
     * Shared submit path: everything decidable synchronously (unsupported UF,
     * manual-chave-without-fallback, blocked merchant, monthly cap, stale/dup
     * replacement) fails fast with a localized 4xx, then the receipt is persisted
     * PROCESSING for the async ingestion to fill in.
     */
    private Receipt validateAndPersistProcessing(User user, String qrPayload) {
        var chave = sefazIngestionService.resolveChave(qrPayload);
        log.info("submit chave={}", LogMasker.chave(chave));

        // Fail unsupported UFs up front with a localized 400 — the async path
        // would only surface a raw FAILED_PARSE key after polling.
        var uf = ChaveAcessoParser.extractUf(chave);
        sefazIngestionService.requireSupported(uf);
        // A manually-typed bare chave has no QR signature. Portals that require it
        // can only be served by the paid by-chave fallback — and RS not even by that
        // (gov.br wall). Reject up front instead of failing (or spending) async.
        if (ChaveAcessoParser.isBareChave(qrPayload) && !sefazIngestionService.supportsBareChave(uf)) {
            log.info("submit rejected reason=manual_chave_unsupported uf={}", uf);
            throw new ManualChaveUnsupportedException(uf.name());
        }
        // A CNPJ we've already classified as food service (or admin-blocked) fails
        // right here with the localized message — nothing is stored at all. The
        // chave embeds the CNPJ, so no fetch is needed. First-time merchants pass
        // through and are gated during async ingestion instead.
        if (merchantSupportGate.isKnownBlockedCnpj(ChaveAcessoParser.extractCnpj(chave))) {
            log.info("submit rejected reason=merchant_unsupported cnpj={}", ChaveAcessoParser.extractCnpj(chave));
            throw new UnsupportedMerchantException();
        }
        enforceMonthlyReceiptCap(user);
        replaceStalePriorOrRejectConfirmedDuplicate(user, chave);

        var receipt = persistProcessing(user, qrPayload, chave);
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receipt.getId()));
        log.info("submit ok status=PROCESSING (ingestion dispatched)");
        return receipt;
    }

    /**
     * Dispatch the slow SEFAZ work only AFTER this transaction commits, so the
     * background thread reads a committed PROCESSING row (no read-before-commit race).
     * A pool rejection (TaskRejectedException) fails the already-committed row, or
     * the FE would poll forever.
     */
    private void dispatchAfterCommit(UUID receiptId, Runnable ingestTask) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        ingestTask.run();
                    } catch (RuntimeException ex) {
                        receiptIngestionService.markFailed(receiptId, ex);
                    }
                }
            });
        } else {
            ingestTask.run();
        }
    }

    /**
     * Persist the receipt as PROCESSING before the SEFAZ fetch. chave + uf are
     * derivable from the QR payload up front (both are NOT NULL columns); the
     * rest of the fields + items are filled in by the async ingestion once the
     * DANFE is parsed.
     */
    private Receipt persistProcessing(User user, String qrPayload, String chave) {
        var receipt = Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(chave)
                .uf(ChaveAcessoParser.extractUf(chave))
                .qrPayload(qrPayload)
                .status(ReceiptStatus.PROCESSING)
                .build();
        return receiptRepository.save(receipt);
    }

    /**
     * FREE plan caps receipts per calendar month. Counts ALL statuses this month
     * so reject/delete-and-resubmit can't game the limit; PRO (limit MAX_VALUE)
     * bypasses entirely.
     */
    public void enforceMonthlyReceiptCap(User user) {
        var monthlyLimit = subscriptionGate.monthlyReceiptLimit(user);
        if (monthlyLimit == Integer.MAX_VALUE) {
            return;
        }
        var startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
        var thisMonth = receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.getId(), startOfMonth);
        if (thisMonth >= monthlyLimit) {
            log.info("paywall.blocked user={} feature={} thisMonth={} limit={}",
                    LogMasker.email(user.getEmail()), Feature.RECEIPT_UPLOAD_UNLIMITED, thisMonth, monthlyLimit);
            throw new PaywallException(Feature.RECEIPT_UPLOAD_UNLIMITED.name());
        }
    }

    /**
     * Per-household uniqueness rule. A household can't double-import its own
     * CONFIRMED receipt (downstream data is already committed — price
     * observations, audit rows, notifications) → reject. But a prior row in any
     * non-final state (PENDING_CONFIRMATION: closed app mid-review; REJECTED:
     * changed mind; FAILED_PARSE: parser fix landed) is stale → discard it so the
     * fresh submission takes its place. Different households may always both
     * record the same fiscal event.
     */
    private void replaceStalePriorOrRejectConfirmedDuplicate(User user, String chave) {
        receiptRepository.findByHouseholdIdAndChaveAcesso(user.getHousehold().getId(), chave)
                .ifPresent(existing -> {
                    if (existing.getStatus() == ReceiptStatus.CONFIRMED) {
                        log.info("submit rejected: chave {} already CONFIRMED in household {}",
                                LogMasker.chave(chave), user.getHousehold().getId());
                        throw new ReceiptAlreadyIngestedException(chave);
                    }
                    log.info("submit replacing stale chave {} (status_was={}) in household {}",
                            LogMasker.chave(chave), existing.getStatus(), user.getHousehold().getId());
                    receiptRepository.delete(existing);
                    receiptRepository.flush();
                });
    }

    @Transactional(readOnly = true)
    public Page<ReceiptSummaryResponse> list(User user,
                                             LocalDateTime from,
                                             LocalDateTime to,
                                             String cnpjEmitente,
                                             List<ProductCategory> categories,
                                             ReceiptStatus status,
                                             String search,
                                             Pageable pageable) {
        var cnpj = Optional.ofNullable(cnpjEmitente).map(String::trim).filter(trimmed -> !trimmed.isBlank()).orElse(null);
        var trimmedSearch = Optional.ofNullable(search).map(String::trim).filter(trimmed -> !trimmed.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "issuedAt"))
                : pageable;
        var spec = ReceiptSpecifications.forSearch(
                user.getHousehold().getId(), from, to, cnpj, categories, status, trimmedSearch, true, null);
        var page = receiptRepository.findAll(spec, sortedPageable);
        var householdId = user.getHousehold().getId();
        var cnpjs = page.getContent().stream()
                .map(Receipt::getCnpjEmitente)
                .filter(receiptCnpj -> receiptCnpj != null)
                .distinct()
                .toList();
        var overrides = marketNameService.resolveNames(householdId, cnpjs);
        return page.map(receipt -> ReceiptSummaryResponse.from(receipt)
                .withMarketFriendlyName(marketNameService.applyOverride(
                        overrides, receipt.getCnpjEmitente(), receipt.getMarketName())));
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(User user, UUID receiptId) {
        return toResponse(user, loadOwned(user, receiptId));
    }

    /**
     * The household's manual category correction for a receipt item's product —
     * "evidence, not truth": it overrides only this household's view (the global
     * product is untouched).
     *
     * <p>The override is anchored to a {@link Product}. If the item isn't linked to
     * one yet (an unrecognized line the canonicalizer left unmatched), we
     * link-or-create the product on the spot so the correction has somewhere to
     * live AND propagates to future purchases of the same item.
     */
    @Transactional
    public ReceiptResponse updateItemCategory(User user, UUID receiptId, UUID itemId, ProductCategory category) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(itemId));
        var receipt = loadOwned(user, receiptId);
        var item = receipt.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ReceiptItemNotFoundException::new);
        var product = item.getProduct() != null
                ? item.getProduct()
                : canonicalizationService.linkOrCreateProduct(receipt, item);
        categoryOverrideService.setOverride(user, product, category);
        log.info("item.category.override product={} category={}", product.getId(), category);
        return toResponse(user, receipt);
    }

    /** Build a ReceiptResponse with the household's category overrides applied. */
    private ReceiptResponse toResponse(User user, Receipt receipt) {
        var productIds = receipt.getItems().stream()
                .filter(item -> item.getProduct() != null)
                .map(item -> item.getProduct().getId())
                .distinct()
                .toList();
        var overrides = categoryOverrideService.overridesByProduct(user.getHousehold().getId(), productIds);
        return withFriendlyName(user.getHousehold().getId(),
                receipt, ReceiptResponse.from(receipt, overrides, suggestedCategories(receipt)));
    }

    /**
     * Best-effort category preview for items not yet linked to a product, so
     * the review screen shows what confirm() will apply instead of everything
     * uncategorized. Mirrors the confirm-time cascade (EAN product → EAN
     * catalog → alias → dictionary) read-only — nothing persisted until confirm,
     * and dictionary improvements show up instantly.
     */
    private Map<UUID, String> suggestedCategories(Receipt receipt) {
        if (receipt.getStatus() != ReceiptStatus.PENDING_CONFIRMATION) return Map.of();
        var suggestions = new HashMap<UUID, String>();
        for (var item : receipt.getItems()) {
            if (item.getProduct() != null) continue;
            canonicalizationService.previewCategory(item)
                    .ifPresent(category -> suggestions.put(item.getId(), category.name()));
        }
        return suggestions;
    }

    /**
     * Confirm a pending receipt: apply any user exclusions, mark CONFIRMED, then
     * fan out to the downstream consumers (canonicalize → personal promos → price
     * index → market registry → notifications → savings). Returns the receipt plus
     * the personal promos detected for this purchase.
     */
    @Transactional
    public ConfirmReceiptResponse confirm(User user, UUID receiptId, ConfirmReceiptRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        log.info("confirm started");
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        applyItemExclusions(receipt, request);
        return doConfirm(user, receipt);
    }

    /**
     * Auto-confirm a receipt the user left PENDING too long (the sweeper). No user
     * session and no exclusions — the parse is authoritative SEFAZ data, contribution
     * still respects {@code contributionOptIn}, and the user can edit/delete after.
     * Idempotent: silently skips if the row already left PENDING_CONFIRMATION.
     */
    @Transactional
    public void confirmStale(UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElse(null);
        if (receipt == null || receipt.getStatus() != ReceiptStatus.PENDING_CONFIRMATION) {
            return;
        }
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        log.info("confirm auto-stale started");
        doConfirm(receipt.getUser(), receipt);
    }

    /** The confirm fan-out shared by the manual and the auto (sweeper) paths. */
    private ConfirmReceiptResponse doConfirm(User user, Receipt receipt) {
        receipt.setStatus(ReceiptStatus.CONFIRMED);
        receipt.setConfirmedAt(LocalDateTime.now());
        canonicalizationService.canonicalize(receipt);
        snapshotCategories(receipt);
        var personalPromos = promoDetector.detectPersonalPromos(receipt);
        priceIndexService.recordContributions(receipt);
        marketLocationService.registerMarketFromReceipt(receipt);
        notifyPersonalPromos(user, receipt, personalPromos);
        var saved = receiptRepository.save(receipt);
        attributeSavings(saved);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("confirm ok status=CONFIRMED personalPromos={}", personalPromos.size());
        return new ConfirmReceiptResponse(
                withFriendlyName(user.getHousehold().getId(), saved, ReceiptResponse.from(saved)), personalPromos);
    }

    /**
     * Freeze each item's category as seen at confirmation ("new knowledge only
     * affects new entries"): the product's live category keeps improving for
     * FUTURE purchases, but this household's confirmed history displays and
     * aggregates by the snapshot. Runs right after canonicalization so newly
     * linked/created products contribute their category.
     */
    private void snapshotCategories(Receipt receipt) {
        for (var item : receipt.getItems()) {
            if (item.getProduct() != null && item.getProduct().getCategory() != null) {
                item.setCategoryAtConfirmation(item.getProduct().getCategory());
            }
        }
    }

    /**
     * Mark the user's chosen items as excluded BEFORE downstream processing, so
     * canonicalization, promo detection, and price-index contributions all skip
     * them. No-op when the request carries no exclusions.
     */
    private void applyItemExclusions(Receipt receipt, ConfirmReceiptRequest request) {
        var excludedIds = request != null && request.excludedItemIds() != null
                ? Set.copyOf(request.excludedItemIds())
                : Set.<UUID>of();
        if (excludedIds.isEmpty()) {
            return;
        }
        var excludedCount = 0;
        for (var item : receipt.getItems()) {
            if (excludedIds.contains(item.getId())) {
                item.setExcluded(true);
                excludedCount++;
            }
        }
        log.info("confirm.exclusions applied={}/{} items", excludedCount, receipt.getItems().size());
    }

    /**
     * Best-effort Phase D savings attribution — runs AFTER confirmation +
     * observations are committed and is fully isolated: any failure here is
     * caught and logged so analytics can NEVER break a confirm.
     */
    private void attributeSavings(Receipt receipt) {
        try {
            savingsAttributionService.attribute(receipt);
        } catch (RuntimeException ex) {
            log.warn("attribution.failed receipt={} reason={}", abbrev(receipt.getId()), ex.getMessage());
        }
    }

    private void notifyPersonalPromos(User user, Receipt receipt, List<PromoDetector.PersonalPromo> promos) {
        if (!promos.isEmpty() && !notificationRuleService.isEnabled(user, NotificationType.PROMO_PERSONAL)) {
            log.debug("personal_promo.skipped user_disabled count={}", promos.size());
            return;
        }
        for (var promo : promos) {
            var title = "Você economizou em " + promo.productName();
            var body = String.format("No %s você pagou R$ %s no %s — %s%% abaixo do que normalmente paga.",
                    promo.productName(), promo.paidPrice(), receipt.getMarketName(), promo.savingsPct());
            notificationService.notify(new NotificationPayload(
                    user,
                    NotificationType.PROMO_PERSONAL,
                    title, body,
                    Map.of(
                            "receiptId", receipt.getId().toString(),
                            "productId", promo.productId().toString(),
                            "savingsPct", promo.savingsPct(),
                            "paidPrice", promo.paidPrice(),
                            "historicalMedian", promo.historicalMedian()
                    )
            ));
        }
    }

    /**
     * User-initiated hard delete. Removes the receipt + its items + the
     * audit rows that link the household to anonymized {@code PriceObservation}
     * entries (cascaded by FK). Does NOT delete the observations themselves —
     * once contributed, anonymized price data stays in the community index.
     * This is by design (LGPD right-to-deletion of personal data, while
     * preserving anonymized aggregates) and is enforced by the schema:
     * {@code price_observation_audits.receipt_id} has {@code ON DELETE
     * CASCADE}, but {@code price_observations} has no FK back to receipts.
     * Frees the chave for re-import within the same household.
     */
    @Transactional
    public void delete(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        var householdId = receipt.getHousehold().getId();
        receiptRepository.delete(receipt);
        householdCacheGen.bump(householdId);
        log.info("delete ok status_was={}", receipt.getStatus());
    }

    /**
     * Admin-only: re-runs parsing on the stored raw HTML, replaces the
     * existing items with the freshly-parsed ones, and resets the receipt
     * to PENDING_CONFIRMATION so the owner can review and re-confirm.
     *
     * <p>Use case: a parser bug is fixed and we want to re-process old
     * receipts without forcing users to re-scan their QR codes.
     *
     * <p>Caveat: if the receipt was previously CONFIRMED, its old
     * PriceObservation rows remain. They'll be joined by new ones when
     * the user re-confirms. Acceptable at admin scale; revisit if this
     * endpoint ever gets bulk usage.
     */
    @Transactional
    public ReceiptResponse reparse(UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = receiptRepository.findById(receiptId).orElseThrow(ReceiptNotFoundException::new);
        if (receipt.getRawHtml() == null || receipt.getRawHtml().isBlank()) {
            throw new ReceiptNotEditableException("RAW_HTML_MISSING");
        }
        log.info("reparse start status_was={} chave={}",
                receipt.getStatus(), LogMasker.chave(receipt.getChaveAcesso()));

        var parsed = sefazIngestionService.reparseStored(
                receipt.getUf(), receipt.getRawHtml(), receipt.getChaveAcesso(), receipt.getSourceUrl());

        receipt.getItems().clear();
        parsed.items().forEach(parsedItem -> receipt.addItem(toReceiptItem(parsedItem)));
        receipt.setMarketName(parsed.marketName());
        receipt.setMarketAddress(parsed.marketAddress());
        receipt.setIssuedAt(parsed.issuedAt());
        receipt.setTotalAmount(parsed.totalAmount());
        receipt.setDiscountTotal(parsed.discountTotal());
        receipt.setApproxTaxFederal(parsed.approxTaxFederal());
        receipt.setApproxTaxEstadual(parsed.approxTaxEstadual());
        receipt.setCnpjEmitente(parsed.cnpjEmitente());
        receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
        receipt.setConfirmedAt(null);
        receipt.setParseErrorReason(null);
        var saved = receiptRepository.save(receipt);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("reparse ok items={} total={} market='{}'",
                saved.getItems().size(), saved.getTotalAmount(), saved.getMarketName());
        return withFriendlyName(saved.getHousehold().getId(), saved, ReceiptResponse.from(saved));
    }

    @Transactional
    public ReceiptResponse reject(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        receipt.setStatus(ReceiptStatus.REJECTED);
        var saved = receiptRepository.save(receipt);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("reject ok status=REJECTED");
        return withFriendlyName(user.getHousehold().getId(), saved, ReceiptResponse.from(saved));
    }

    @Transactional
    public ReceiptResponse updateItem(User user, UUID receiptId, UUID itemId, UpdateReceiptItemRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(itemId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        var item = receipt.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ReceiptItemNotFoundException::new);
        applyUpdate(item, request);
        receiptItemRepository.save(item);
        // Remember the friendly name household-wide so future receipts of
        // the same Product inherit it. No-op when item isn't linked yet.
        householdProductAliasService.rememberFromItem(receipt.getHousehold(), item);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("item.updated description='{}' qty={} totalPrice={}",
                item.getRawDescription(), item.getQuantity(), item.getTotalPrice());
        return toResponse(user, receipt);
    }

    /**
     * Household "personal layer" edit — allowed at ANY status (including after
     * confirmation), unlike {@link #updateItem}. Touches only how this household
     * sees/accounts the line: "not mine" ({@code excludedFromPersonal}), friendly
     * name, and manual paid price. The immutable SEFAZ fields (quantity, shelf
     * price, EAN) and the already-emitted price-index observation are untouched,
     * so nothing here rewrites the shared index.
     */
    @Transactional
    public ReceiptResponse updatePersonalItem(User user, UUID receiptId, UUID itemId,
                                              UpdateItemPersonalRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(itemId));
        var receipt = loadOwned(user, receiptId);
        var item = receipt.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ReceiptItemNotFoundException::new);
        if (request.excludedFromPersonal() != null) {
            item.setExcludedFromPersonal(request.excludedFromPersonal());
        }
        if (request.friendlyDescription() != null) {
            item.setFriendlyDescription(request.friendlyDescription().isBlank()
                    ? null : request.friendlyDescription());
        }
        applyPaidPrice(item, request.paidTotalPrice(), request.paidUnitPrice());
        receiptItemRepository.save(item);
        householdProductAliasService.rememberFromItem(receipt.getHousehold(), item);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("item.personal.updated notMine={} promo={}",
                item.isExcludedFromPersonal(), item.isPromotional());
        return toResponse(user, receipt);
    }

    /**
     * Add a missing item to a PENDING_CONFIRMATION receipt. Use case: SVRS
     * parser missed a line. Position appended to the end of the existing
     * items list.
     */
    @Transactional
    public ReceiptResponse addItem(User user, UUID receiptId, AddReceiptItemRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        var nextLine = receipt.getItems().stream()
                .map(ReceiptItem::getLineNumber)
                .filter(lineNumber -> lineNumber != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        var item = ReceiptItem.builder()
                .lineNumber(nextLine)
                .rawDescription(request.rawDescription())
                .friendlyDescription(request.friendlyDescription())
                .ean(request.ean())
                .quantity(request.quantity())
                .unit(request.unit())
                .unitPrice(request.unitPrice())
                .totalPrice(request.totalPrice())
                .build();
        receipt.addItem(item);
        receiptItemRepository.save(item);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("item.added line={} description='{}' qty={} totalPrice={}",
                nextLine, item.getRawDescription(), item.getQuantity(), item.getTotalPrice());
        return toResponse(user, receipt);
    }

    /** Apply the household's custom market display name to the response (sibling field; original untouched). */
    private ReceiptResponse withFriendlyName(UUID householdId, Receipt receipt, ReceiptResponse response) {
        var friendly = marketNameService.resolve(householdId, receipt.getCnpjEmitente(), receipt.getMarketName());
        return response.withMarketFriendlyName(friendly)
                .withParseErrorMessage(localizedParseError(receipt));
    }

    /**
     * Translates the machine {@code parseErrorReason} ("key:args") into a
     * user-showable message in the request's locale. Unknown keys fall back to
     * the generic parse-failure message — never null for a FAILED_PARSE row.
     */
    private String localizedParseError(Receipt receipt) {
        if (receipt.getStatus() != ReceiptStatus.FAILED_PARSE || receipt.getParseErrorReason() == null) {
            return null;
        }
        var reason = receipt.getParseErrorReason();
        var separatorIndex = reason.indexOf(':');
        var key = separatorIndex < 0 ? reason : reason.substring(0, separatorIndex);
        var argument = separatorIndex < 0 ? "" : reason.substring(separatorIndex + 1);
        try {
            return localizedMessageService.translate(key, argument);
        } catch (RuntimeException ex) {
            return localizedMessageService.translate("receipt.parse.failed", argument);
        }
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    private Receipt loadOwned(User user, UUID receiptId) {
        var receipt = receiptRepository.findByIdWithItemsAndProducts(receiptId)
                .orElseThrow(ReceiptNotFoundException::new);
        if (!receipt.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ReceiptNotFoundException();
        }
        return receipt;
    }

    private void requirePending(Receipt receipt) {
        if (receipt.getStatus() != ReceiptStatus.PENDING_CONFIRMATION) {
            throw new ReceiptNotEditableException(receipt.getStatus().name());
        }
    }

    /** Single mapping from a SEFAZ-parsed line to a persisted item (reparse). */
    private static ReceiptItem toReceiptItem(ParsedReceiptItem parsedItem) {
        return ReceiptItem.builder()
                .lineNumber(parsedItem.lineNumber())
                .rawDescription(parsedItem.rawDescription())
                .ean(parsedItem.ean())
                .quantity(parsedItem.quantity())
                .unit(parsedItem.unit())
                .unitPrice(parsedItem.unitPrice())
                .totalPrice(parsedItem.totalPrice())
                .nfcePromoFlag(parsedItem.nfcePromoFlag())
                .build();
    }

    private void applyUpdate(ReceiptItem item, UpdateReceiptItemRequest request) {
        // rawDescription stays immutable — it's the SEFAZ-issued audit text.
        // Display overrides go on friendlyDescription.
        item.setEan(request.ean());
        item.setQuantity(request.quantity());
        item.setUnit(request.unit());
        item.setUnitPrice(request.unitPrice());
        item.setTotalPrice(request.totalPrice());
        if (request.excluded() != null) item.setExcluded(request.excluded());
        if (request.friendlyDescription() != null) {
            item.setFriendlyDescription(request.friendlyDescription().isBlank()
                    ? null : request.friendlyDescription());
        }
        applyPaidPrice(item, request.paidTotalPrice(), request.paidUnitPrice());
    }

    /**
     * Records the user-entered "price actually paid" for a discounted line. The
     * original {@link ReceiptItem#getTotalPrice() totalPrice} stays as-printed
     * (shelf price); the paid price is stored separately so the app can show the
     * discount. A null paid total clears any previous manual discount. The paid
     * total can never exceed the original — a discount only lowers the price.
     */
    private void applyPaidPrice(ReceiptItem item, BigDecimal paidTotal, BigDecimal requestedPaidUnit) {
        if (paidTotal == null) {
            item.setPaidUnitPrice(null);
            item.setPaidTotalPrice(null);
            return;
        }
        if (item.getTotalPrice() != null && paidTotal.compareTo(item.getTotalPrice()) > 0) {
            throw new InvalidItemPriceException();
        }
        var paidUnit = requestedPaidUnit;
        if (paidUnit == null && item.getQuantity() != null && item.getQuantity().signum() > 0) {
            paidUnit = paidTotal.divide(item.getQuantity(), 4, RoundingMode.HALF_UP);
        }
        item.setPaidUnitPrice(paidUnit);
        item.setPaidTotalPrice(paidTotal);
    }
}
