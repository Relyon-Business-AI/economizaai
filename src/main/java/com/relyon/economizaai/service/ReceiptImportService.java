package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.ReceiptImportResponse;
import com.relyon.economizaai.dto.response.ReceiptImportResponse.RejectedChave;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.RsChaveReconsultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bulk import of receipts from a list of access keys (chaves) — the onboarding
 * path that fills a new user's history from their Nota Fiscal Gaúcha export
 * without scanning each nota. Only RS is reconsultable today (NFC-e 65 + NF-e 55).
 *
 * <p>Each eligible chave is persisted as {@link ReceiptStatus#IMPORT_QUEUED} — NOT
 * dispatched to the async pool. The paced {@link com.relyon.economizaai.service.sefaz.ImportReconsultWorker}
 * then reconsults a few at a time. This is deliberate: dispatching ~50 slow SEFAZ
 * reconsults at once starved the shared ingest pool and the ProcessingReceiptSweeper
 * force-failed the backlog ({@code receipt.processing.timeout}). Validated 2026-09-22
 * — see {@code docs/ONBOARDING_IMPORT.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptImportService {

    // NFG CSV renders the chave as two space-separated blocks; also accept a contiguous 44.
    private static final Pattern TWO_BLOCK_CHAVE = Pattern.compile("(\\d{20,24})\\s+(\\d{20,24})");
    private static final Pattern CONTIGUOUS_CHAVE = Pattern.compile("\\d{44}");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private final ReceiptRepository receiptRepository;
    private final ReceiptService receiptService;
    private final MerchantSupportGate merchantSupportGate;
    private final LocalizedMessageService localizedMessageService;

    /**
     * Queue every reconsultable chave as IMPORT_QUEUED and report the rest. The paced
     * worker picks them up; the FE polls {@code GET /receipts/{id}}.
     */
    @Transactional
    public ReceiptImportResponse importChaves(User user, List<String> rawChaves) {
        var unique = new LinkedHashSet<String>();
        for (var raw : rawChaves) {
            unique.add(NON_DIGITS.matcher(raw == null ? "" : raw).replaceAll(""));
        }
        var queuedIds = new ArrayList<UUID>();
        var rejected = new ArrayList<RejectedChave>();
        var capReached = false;

        for (var chave : unique) {
            if (capReached) {
                rejected.add(reject(chave, "receipt.import.cap_reached"));
                continue;
            }
            var rejection = classify(user, chave);
            if (rejection != null) {
                rejected.add(rejection);
                continue;
            }
            try {
                receiptService.enforceMonthlyReceiptCap(user);
            } catch (PaywallException ex) {
                capReached = true;
                rejected.add(reject(chave, "receipt.import.cap_reached"));
                continue;
            }
            queuedIds.add(queue(user, chave));
        }

        log.info("import.done received={} queued={} rejected={}", unique.size(), queuedIds.size(), rejected.size());
        return new ReceiptImportResponse(unique.size(), queuedIds.size(), queuedIds, rejected.size(), rejected);
    }

    /** Extracts the chaves from a raw Nota Fiscal Gaúcha CSV export, then imports them. */
    @Transactional
    public ReceiptImportResponse importFromNfgCsv(User user, String csv) {
        return importChaves(user, extractChaves(csv));
    }

    /**
     * Re-queue failed/stale import receipts for another reconsult (individual or batch).
     * Household-scoped; CONFIRMED and non-reconsultable receipts are skipped. Returns how
     * many were actually re-queued.
     */
    @Transactional
    public int retry(User user, List<UUID> receiptIds) {
        var householdId = user.getHousehold().getId();
        var requeued = 0;
        for (var receiptId : receiptIds) {
            var receipt = receiptRepository.findById(receiptId).orElse(null);
            if (receipt == null || !receipt.getHousehold().getId().equals(householdId)) continue;
            if (receipt.getStatus() == ReceiptStatus.CONFIRMED) continue;
            var chave = receipt.getChaveAcesso();
            if (chave == null || !RsChaveReconsultService.isReconsultable(chave)) continue;
            receipt.setStatus(ReceiptStatus.IMPORT_QUEUED);
            receipt.setParseErrorReason(null);
            receiptRepository.save(receipt);
            requeued++;
        }
        log.info("import.retry requested={} requeued={}", receiptIds.size(), requeued);
        return requeued;
    }

    static List<String> extractChaves(String csv) {
        var found = new LinkedHashSet<String>();
        if (csv != null) {
            var twoBlock = TWO_BLOCK_CHAVE.matcher(csv);
            while (twoBlock.find()) {
                var joined = twoBlock.group(1) + twoBlock.group(2);
                if (joined.length() == 44 && ChaveAcessoParser.hasValidCheckDigit(joined)) {
                    found.add(joined);
                }
            }
            var contiguous = CONTIGUOUS_CHAVE.matcher(csv);
            while (contiguous.find()) {
                if (ChaveAcessoParser.hasValidCheckDigit(contiguous.group())) {
                    found.add(contiguous.group());
                }
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * Returns a rejection when the chave can't be imported, or null when it's eligible.
     * A prior CONFIRMED nota is a real duplicate (reject); any other prior state
     * (FAILED/PENDING/QUEUED…) is stale — delete it so this import retries the chave.
     */
    private RejectedChave classify(User user, String chave) {
        if (chave.length() != 44 || !ChaveAcessoParser.hasValidCheckDigit(chave)) {
            return reject(chave, "receipt.import.invalid_chave");
        }
        if (!RsChaveReconsultService.isReconsultable(chave)) {
            return reject(chave, "receipt.import.unsupported");
        }
        if (merchantSupportGate.isKnownBlockedCnpj(ChaveAcessoParser.extractCnpj(chave))) {
            return reject(chave, "receipt.import.merchant_unsupported");
        }
        var existing = receiptRepository
                .findByHouseholdIdAndChaveAcesso(user.getHousehold().getId(), chave)
                .orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ReceiptStatus.CONFIRMED) {
                return reject(chave, "receipt.import.duplicate");
            }
            receiptRepository.delete(existing);
            receiptRepository.flush();
        }
        return null;
    }

    private UUID queue(User user, String chave) {
        var receipt = receiptRepository.save(Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(chave)
                .uf(ChaveAcessoParser.extractUf(chave))
                .qrPayload(chave)
                .origin(ReceiptOrigin.IMPORT)
                .status(ReceiptStatus.IMPORT_QUEUED)
                .build());
        log.info("import.queued rcpt={} chave={}", abbrev(receipt.getId()), LogMasker.chave(chave));
        return receipt.getId();
    }

    private RejectedChave reject(String chave, String reasonKey) {
        return new RejectedChave(chave, reasonKey, localizedMessageService.translate(reasonKey));
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }
}
