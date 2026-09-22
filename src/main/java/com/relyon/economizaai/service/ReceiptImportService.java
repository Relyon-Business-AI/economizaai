package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.ReceiptImportResponse;
import com.relyon.economizaai.dto.response.ReceiptImportResponse.RejectedChave;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.RsChaveReconsultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bulk import of receipts from a list of access keys (chaves) — the onboarding
 * path that fills a new user's history from their Nota Fiscal Gaúcha export
 * without scanning each nota. Each eligible RS chave is reconsulted on a public
 * SEFAZ portal ({@link RsChaveReconsultService}) and ingested through the normal
 * pipeline; ineligible chaves are rejected up front with a localized reason.
 *
 * <p>Validated 2026-09-22 — see {@code docs/ONBOARDING_IMPORT.md}. Only RS is
 * reconsultable today (NFC-e 65 + NF-e 55); other UFs are rejected as unsupported.
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
    private final ReceiptIngestionService receiptIngestionService;
    private final ReceiptService receiptService;
    private final MerchantSupportGate merchantSupportGate;
    private final LocalizedMessageService localizedMessageService;

    /**
     * Queue every reconsultable chave and report the rest. Runs in one transaction
     * so the PROCESSING rows commit together; the slow SEFAZ reconsults are
     * dispatched only after commit (like {@code ReceiptService.submit}).
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
            queuedIds.add(queueReconsult(user, chave));
        }

        log.info("import.done received={} queued={} rejected={}", unique.size(), queuedIds.size(), rejected.size());
        return new ReceiptImportResponse(unique.size(), queuedIds.size(), queuedIds, rejected.size(), rejected);
    }

    /** Extracts the chaves from a raw Nota Fiscal Gaúcha CSV export, then imports them. */
    @Transactional
    public ReceiptImportResponse importFromNfgCsv(User user, String csv) {
        return importChaves(user, extractChaves(csv));
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

    /** Returns a rejection when the chave can't be imported, or null when it's eligible. */
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
        var alreadyPresent = receiptRepository
                .findByHouseholdIdAndChaveAcesso(user.getHousehold().getId(), chave)
                .isPresent();
        if (alreadyPresent) {
            return reject(chave, "receipt.import.duplicate");
        }
        return null;
    }

    private UUID queueReconsult(User user, String chave) {
        var receipt = receiptRepository.save(Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(chave)
                .uf(ChaveAcessoParser.extractUf(chave))
                .qrPayload(chave)
                .status(ReceiptStatus.PROCESSING)
                .build());
        receiptRepository.flush();
        var receiptId = receipt.getId();
        dispatchAfterCommit(receiptId, () -> receiptIngestionService.ingestReconsult(receiptId, chave));
        log.info("import.queued rcpt={} chave={}", abbrev(receiptId), LogMasker.chave(chave));
        return receiptId;
    }

    /**
     * Dispatch the slow reconsult only AFTER commit so the async thread reads a
     * committed PROCESSING row; a pool rejection fails the row (else the FE polls
     * forever). Mirrors {@code ReceiptService.dispatchAfterCommit}.
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

    private RejectedChave reject(String chave, String reasonKey) {
        return new RejectedChave(chave, reasonKey, localizedMessageService.translate(reasonKey));
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }
}
