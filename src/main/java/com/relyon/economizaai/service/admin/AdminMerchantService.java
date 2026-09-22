package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.GreyMerchantResponse;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Admin review loop for the merchant support gate: lists grey-zone merchants
 * (scanned by real users but outside every supported/blocked segment) and
 * applies the verdict. Promoting a merchant backfills the collaborative index
 * from its already-confirmed receipts, so nothing scanned while it waited in
 * the queue is lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMerchantService {

    private static final List<MerchantSegment> GREY_SEGMENTS =
            List.of(MerchantSegment.OTHER, MerchantSegment.UNKNOWN);

    private final MarketLocationRepository marketLocationRepository;
    private final ReceiptRepository receiptRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final PriceIndexService priceIndexService;

    @Transactional(readOnly = true)
    public List<GreyMerchantResponse> listGreyMerchants() {
        return marketLocationRepository.findAllBySupportOverrideIsNullAndSegmentIn(GREY_SEGMENTS).stream()
                .map(market -> GreyMerchantResponse.from(
                        market, receiptRepository.countByCnpjEmitente(market.getCnpj())))
                .sorted(Comparator.comparingLong(GreyMerchantResponse::receiptCount).reversed())
                .toList();
    }

    /**
     * Applies the admin verdict. On SUPPORTED, already-confirmed receipts of
     * this merchant are pushed into the price index (skipping any that
     * contributed before). Existing user history is never touched on BLOCKED —
     * only future scans are rejected.
     */
    @Transactional
    public SupportOverrideResult setSupportOverride(String cnpj, MerchantSupportOverride override) {
        var market = marketLocationRepository.findByCnpj(cnpj)
                .orElseThrow(() -> new MarketNotFoundException(cnpj));
        market.setSupportOverride(override);
        marketLocationRepository.save(market);
        var backfilled = override == MerchantSupportOverride.SUPPORTED ? backfillObservations(cnpj) : 0;
        log.info("merchant.support_override cnpj={} override={} backfilledReceipts={}",
                cnpj, override, backfilled);
        return new SupportOverrideResult(cnpj, override, backfilled);
    }

    private int backfillObservations(String cnpj) {
        var confirmedReceipts = receiptRepository.findAllByCnpjEmitenteAndStatus(cnpj, ReceiptStatus.CONFIRMED);
        var backfilled = 0;
        for (var receipt : confirmedReceipts) {
            if (auditRepository.existsByReceiptId(receipt.getId())) continue;
            if (priceIndexService.recordContributions(receipt) > 0) backfilled++;
        }
        return backfilled;
    }

    public record SupportOverrideResult(String cnpj, MerchantSupportOverride override, int backfilledReceipts) {}
}
