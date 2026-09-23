package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.config.MdcContextFilter;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.ExperimentalStateFailedException;
import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.exception.UnsupportedMerchantException;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.extraction.EanCatalogEnrichmentService;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Function;

/**
 * Background SEFAZ ingestion for a receipt already persisted as PROCESSING.
 *
 * <p>The slow work — fetch from SEFAZ, solve the captcha (up to a couple of
 * minutes), parse the DANFE — runs off the request thread so {@code POST
 * /receipts} returns immediately. On success the row flips to
 * PENDING_CONFIRMATION with its items; on a parse failure it flips to
 * FAILED_PARSE with the reason. The FE polls {@code GET /receipts/{id}} until
 * the status leaves PROCESSING.
 *
 * <p>Deliberately NOT {@code @Transactional} at the method level: the SEFAZ
 * fetch can hold for minutes and a transaction spanning it would pin a Hikari
 * connection per in-flight receipt, starving the rest of the app. Instead the
 * DB work brackets the HTTP work in short {@link TransactionTemplate} blocks —
 * and because the template commits inside the try, commit-time failures
 * (constraint violations at flush) land in the catch and mark the receipt
 * FAILED_PARSE instead of stranding it in PROCESSING.
 *
 * <p>Separate bean (not a method on ReceiptService) because Spring AOP only
 * applies {@code @Async} across bean boundaries — a self-invocation would run
 * inline on the request thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptIngestionService {

    private final ReceiptRepository receiptRepository;
    private final SefazIngestionService sefazIngestionService;
    private final TransactionTemplate transactionTemplate;
    private final EanCatalogEnrichmentService eanCatalogEnrichmentService;
    private final MarketLocationService marketLocationService;
    private final MerchantSupportGate merchantSupportGate;
    private final RsChaveReconsultService rsChaveReconsultService;

    /**
     * Fetch + parse the PROCESSING receipt, then transition it. Runs on the
     * receipt-ingest pool. Exceptions are handled internally (recorded as
     * FAILED_PARSE) — nothing useful can propagate off an async thread.
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    public void ingest(UUID receiptId, String qrPayload) {
        // Web / photo upload: not "the app" and can't fetch on-device, so a blocked state is a
        // plain FAILED_PARSE (no "update the app" hint, which wouldn't make sense here).
        ingestResolved(receiptId, receipt -> sefazIngestionService.fetch(qrPayload, receipt.getUser().getId()),
                false, null);
    }

    /**
     * As {@link #ingest(UUID, String)} but {@code canDeviceRetry} says whether the
     * submitting CLIENT can resolve a blocked-state failure on-device. Only true for
     * apps new enough to know the {@code NEEDS_DEVICE_FETCH} status and re-post via
     * {@code /device-content}; older apps must get {@code FAILED_PARSE} (rendering an
     * unknown status crashes their receipts list).
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    public void ingest(UUID receiptId, String qrPayload, boolean canDeviceRetry) {
        // Mobile scan (attribute paid SEFAZ calls to the owner for metering). canDeviceRetry=true
        // (app knows NEEDS_DEVICE_FETCH) → resolve on-device; false (older app) → FAILED_PARSE
        // telling the user to update, since this state only works on the newer app.
        ingestResolved(receiptId, receipt -> sefazIngestionService.fetch(qrPayload, receipt.getUser().getId()),
                canDeviceRetry, "receipt.state.app_update_required");
    }

    /**
     * As {@link #ingest} but the SEFAZ page was fetched by the CLIENT on-device
     * (its own residential IP) — for portals that block our datacenter server
     * (e.g. Pernambuco). No scraping happens from our IP; we parse the provided
     * content with the exact same per-UF parser and persist pipeline.
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    public void ingestPrefetched(UUID receiptId, String qrPayload, String rawContent) {
        // The app ALREADY fetched on-device; if this still fails it's a real dead end, not an
        // "update the app" case — plain FAILED_PARSE and no NEEDS_DEVICE_FETCH loop.
        ingestResolved(receiptId, receipt -> sefazIngestionService.fromClientContent(
                rawContent, receipt.getChaveAcesso(), receipt.getUf(), sourceUrlOf(qrPayload)), false, null);
    }

    /**
     * As {@link #ingest} but the item data is <b>reconsulted from the bare chave</b>
     * (no QR scan) on a public RS portal — the engine of the CSV/chaves bulk import.
     * Reuses the whole persist pipeline (merchant gate, EAN warm-up, parse-failure
     * keeps the raw HTML) via {@link RsChaveReconsultService}. Not device-retryable:
     * a reconsult dead end is a plain FAILED_PARSE.
     */
    @Async(AsyncConfig.RECEIPT_INGEST_EXECUTOR)
    public void ingestReconsult(UUID receiptId, String chave) {
        ingestResolved(receiptId, receipt -> rsChaveReconsultService.reconsult(chave), false, null);
    }

    /**
     * Shared ingest body: load the PROCESSING row, resolve the document (server
     * fetch or client-provided), parse, and persist — with the same failure
     * handling for every path (parse failure keeps the raw for review; transient
     * DB blips leave the row PROCESSING for the sweeper; anything else fails it).
     */
    private void ingestResolved(UUID receiptId,
                                Function<Receipt, SefazIngestionService.FetchedDocument> resolver,
                                boolean canDeviceRetry,
                                String blockedStateReasonKey) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        try {
            var receipt = receiptRepository.findById(receiptId).orElse(null);
            if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) {
                log.warn("ingest skipped: receipt {} missing or no longer PROCESSING", abbrev(receiptId));
                return;
            }
            var userId = receipt.getUser().getId();
            SefazIngestionService.FetchedDocument fetched;
            try {
                fetched = resolver.apply(receipt);
            } catch (ExperimentalStateFailedException ex) {
                routeExperimentalFailure(receiptId, ex, canDeviceRetry, blockedStateReasonKey);
                return;
            }
            try {
                var parsed = sefazIngestionService.parse(fetched, userId);
                if (rejectUnsupportedMerchant(receiptId, receipt.getOrigin(), receipt.getChaveAcesso(), parsed)) {
                    return;
                }
                warmEanCatalog(parsed);
                persistParsed(receiptId, parsed);
            } catch (ExperimentalStateFailedException ex) {
                routeExperimentalFailure(receiptId, ex, canDeviceRetry, blockedStateReasonKey);
            } catch (ReceiptParseException ex) {
                persistParseFailure(receiptId, fetched, ex);
            }
        } catch (TransientDataAccessException | RecoverableDataAccessException ex) {
            // Transient DB blip (e.g. PSQLException 57P01 "terminating connection due to
            // administrator command" — a DB restart / idle-in-transaction kill). The receipt
            // itself is valid; poisoning it to FAILED_PARSE would force a needless rescan.
            // Leave it PROCESSING so the sweeper (or a re-dispatch) can recover it.
            log.warn("ingest transient DB failure, leaving receipt {} PROCESSING for retry: {}",
                    abbrev(receiptId), ex.getMessage());
        } catch (RuntimeException ex) {
            // SEFAZ fetch / captcha / commit-time failure — mark FAILED_PARSE so the
            // FE stops polling and shows an error instead of spinning forever.
            markFailed(receiptId, ex);
        } finally {
            MDC.remove(MdcContextFilter.RECEIPT_ID);
        }
    }

    private static String sourceUrlOf(String qrPayload) {
        var trimmed = qrPayload == null ? null : qrPayload.trim();
        return trimmed != null && trimmed.toLowerCase().startsWith("http") ? trimmed : null;
    }

    /**
     * Best-effort, UNTRANSACTED catalog warm-up: for item barcodes we don't yet
     * know, fetch them live from the OFF-family API and cache-through, so this
     * receipt (and future ones) can categorize by EAN. Runs before the persist
     * tx opens — the HTTP must never sit inside a DB transaction — and never
     * throws, so a lookup hiccup can't fail an otherwise-good ingest.
     */
    private void warmEanCatalog(ParsedReceipt parsed) {
        if (!eanCatalogEnrichmentService.isEnabled()) return;
        try {
            var eans = parsed.items().stream()
                    .map(ParsedReceiptItem::ean)
                    .filter(ean -> ean != null && !ean.isBlank())
                    .toList();
            eanCatalogEnrichmentService.enrichMissing(eans);
        } catch (RuntimeException ex) {
            log.warn("ean_catalog.enrich failed (ignored) reason={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Merchant support gate, applied UNTRANSACTED right after parse: registers
     * the market (first sighting) and inline-classifies its CNAE, so a
     * food-service merchant is rejected on the very first scan. A blocked
     * merchant leaves only a content-free tombstone — market identification and
     * the localized reason for the FE poll, but NO items and NO raw HTML — the
     * product deliberately stores nothing from unsupported receipts. Grey-zone
     * merchants pass through (their index exclusion happens at confirm time).
     */
    private boolean rejectUnsupportedMerchant(UUID receiptId, ReceiptOrigin origin, String chave, ParsedReceipt parsed) {
        var market = marketLocationService.resolveForIngest(
                parsed.cnpjEmitente(), parsed.marketName(), parsed.marketAddress());
        // Bulk import is stricter than a live scan: only grocery/pharmacy or e-commerce (NF-e 55).
        if (origin == ReceiptOrigin.IMPORT) {
            var segment = market == null ? null : market.getSegment();
            var rejectionKey = merchantSupportGate.importRejectionKey(ChaveAcessoParser.extractModel(chave), segment);
            if (rejectionKey == null) return false;
            persistMerchantRejection(receiptId, parsed, rejectionKey);
            log.info("import rejected reason={} segment={} cnpj={} market='{}'",
                    rejectionKey, segment, parsed.cnpjEmitente(), parsed.marketName());
            return true;
        }
        if (!merchantSupportGate.isBlocked(market)) {
            return false;
        }
        persistMerchantRejection(receiptId, parsed, new UnsupportedMerchantException().getMessageKey());
        log.info("ingest rejected reason=merchant_unsupported segment={} cnpj={} market='{}'",
                market.getSegment(), parsed.cnpjEmitente(), parsed.marketName());
        return true;
    }

    private void persistMerchantRejection(UUID receiptId, ParsedReceipt parsed, String reasonKey) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            receipt.setCnpjEmitente(parsed.cnpjEmitente());
            receipt.setMarketName(parsed.marketName());
            receipt.setParseErrorReason(reasonKey + ":");
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receiptRepository.save(receipt);
        });
    }

    private void persistParsed(UUID receiptId, ParsedReceipt parsed) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            applyParsed(receipt, parsed);
            receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
            receiptRepository.save(receipt);
            log.info("ingest ok status=PENDING_CONFIRMATION items={} total={} market='{}'",
                    receipt.getItems().size(), receipt.getTotalAmount(), receipt.getMarketName());
        });
    }

    private void persistParseFailure(UUID receiptId, SefazIngestionService.FetchedDocument fetched,
                                     ReceiptParseException ex) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            receipt.setRawHtml(fetched.html());
            receipt.setSourceUrl(fetched.sourceUrl());
            receipt.setParseErrorReason(ex.getMessageKey() + ":" + String.join(",", ex.getArguments()));
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receiptRepository.save(receipt);
            log.warn("ingest parse-failed status=FAILED_PARSE reason={} (raw HTML kept for review)",
                    ex.getMessageKey());
        });
    }

    /**
     * The experimental chain gave up (no verified adapter + our datacenter IP can't
     * reach the portal). On the SERVER path this is recoverable — flag the receipt so
     * the app retries the fetch on the user's own (accepted) device. On the DEVICE path
     * (the app already tried) it's a real dead end → FAILED_PARSE, no retry loop.
     */
    private void routeExperimentalFailure(UUID receiptId, ExperimentalStateFailedException ex,
                                          boolean canDeviceRetry, String blockedStateReasonKey) {
        if (canDeviceRetry) {
            persistNeedsDeviceFetch(receiptId, ex);
        } else if (blockedStateReasonKey != null) {
            markBlockedState(receiptId, blockedStateReasonKey);
        } else {
            markFailed(receiptId, ex);
        }
    }

    /**
     * A blocked state (e.g. Pernambuco) that only the newer app can resolve on-device, hit by a
     * client that can't — FAILED_PARSE with a localizable "update the app" reason instead of a
     * generic fetch failure, so the user knows what to do.
     */
    private void markBlockedState(UUID receiptId, String reasonKey) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            receipt.setParseErrorReason(reasonKey);
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receiptRepository.save(receipt);
            log.info("ingest failed_parse reason={} — blocked state, client can't device-fetch", reasonKey);
        });
    }

    private void persistNeedsDeviceFetch(UUID receiptId, ExperimentalStateFailedException ex) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            var receipt = loadIfProcessing(receiptId);
            if (receipt == null) return;
            receipt.setStatus(ReceiptStatus.NEEDS_DEVICE_FETCH);
            receiptRepository.save(receipt);
            log.info("ingest needs-device-fetch uf={} — server blocked, app will retry on-device", ex.getMessage());
        });
    }

    private Receipt loadIfProcessing(UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElse(null);
        if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) {
            log.warn("ingest result discarded: receipt {} missing or no longer PROCESSING", abbrev(receiptId));
            return null;
        }
        return receipt;
    }

    /**
     * Marks the receipt FAILED_PARSE with the exception as the reason. Public so
     * the submit path can also invoke it when the ingest pool rejects the task —
     * otherwise the committed PROCESSING row would never leave that status.
     */
    public void markFailed(UUID receiptId, RuntimeException ex) {
        try {
            transactionTemplate.executeWithoutResult(txStatus ->
                    receiptRepository.findById(receiptId).ifPresent(receipt -> {
                        if (receipt.getStatus() == ReceiptStatus.PROCESSING) {
                            receipt.setParseErrorReason(failureReason(ex));
                            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
                            receiptRepository.save(receipt);
                        }
                    }));
        } catch (RuntimeException inner) {
            log.error("ingest failed AND could not mark FAILED_PARSE for receipt {}", abbrev(receiptId), inner);
        }
        log.warn("ingest fetch/solve failed status=FAILED_PARSE reason={}", ex.getMessage(), ex);
    }

    /**
     * A DomainException (daily cap hit, circuit open, captcha unavailable) carries
     * its own localizable key + args — surface it so the FE renders the real reason
     * ("daily limit reached") instead of a generic fetch failure.
     */
    private static String failureReason(RuntimeException ex) {
        if (ex instanceof DomainException domainEx) {
            return domainEx.getMessageKey() + ":" + String.join(",", domainEx.getArguments());
        }
        return "receipt.sefaz.fetch.failed:" + ex.getClass().getSimpleName();
    }

    private void applyParsed(Receipt receipt, ParsedReceipt parsed) {
        receipt.setChaveAcesso(parsed.chaveAcesso());
        receipt.setUf(ChaveAcessoParser.extractUf(parsed.chaveAcesso()));
        receipt.setCnpjEmitente(parsed.cnpjEmitente());
        receipt.setMarketName(parsed.marketName());
        receipt.setMarketAddress(parsed.marketAddress());
        receipt.setIssuedAt(parsed.issuedAt());
        receipt.setTotalAmount(parsed.totalAmount());
        receipt.setDiscountTotal(parsed.discountTotal());
        receipt.setApproxTaxFederal(parsed.approxTaxFederal());
        receipt.setApproxTaxEstadual(parsed.approxTaxEstadual());
        receipt.setSourceUrl(parsed.sourceUrl());
        receipt.setRawHtml(parsed.rawHtml());
        parsed.items().forEach(parsedItem -> receipt.addItem(toReceiptItem(parsedItem)));
    }

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

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }
}
