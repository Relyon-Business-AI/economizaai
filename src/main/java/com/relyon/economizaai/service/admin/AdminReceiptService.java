package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.ReceiptResponse;
import com.relyon.economizaai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only operations on receipts: cross-household paginated search and
 * detail-by-id without the per-household ownership check that
 * {@link com.relyon.economizaai.service.ReceiptService} enforces for
 * regular users. FAILED_PARSE rows are visible to admins (they're useful
 * for parser debugging) but hidden from end users.
 *
 * <p>Single responsibility: read views over the entire receipt corpus for
 * ops triage. Mutations on receipts (reparse, etc.) live elsewhere.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReceiptService {

    private final ReceiptRepository receiptRepository;
    private final PriceObservationAuditRepository observationAuditRepository;
    private final PriceObservationRepository observationRepository;

    @Transactional(readOnly = true)
    public Page<ReceiptSummaryResponse> list(LocalDateTime from,
                                             LocalDateTime to,
                                             String marketCnpj,
                                             List<ProductCategory> categories,
                                             String search,
                                             UUID householdId,
                                             UnidadeFederativa uf,
                                             ReceiptStatus status,
                                             String parseErrorReason,
                                             boolean includeInternal,
                                             Pageable pageable) {
        var trimmedCnpj = Optional.ofNullable(marketCnpj).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var trimmedSearch = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var trimmedError = Optional.ofNullable(parseErrorReason).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "issuedAt"))
                : pageable;
        // Admin sees FAILED_PARSE rows too (useful for parser triage) — but an
        // explicit status filter narrows to one bucket when passed.
        var spec = ReceiptSpecifications.forSearch(
                householdId, from, to, trimmedCnpj, categories, status, trimmedSearch, false, uf, trimmedError);
        // Off by default: hide receipts from admin/test accounts so a bulk import doesn't flood the list.
        if (!includeInternal) spec = spec.and(ReceiptSpecifications.excludeInternal());
        return receiptRepository.findAll(spec, sortedPageable).map(ReceiptSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElseThrow(ReceiptNotFoundException::new);
        return ReceiptResponse.from(receipt);
    }

    /**
     * Admin data-quality / test cleanup: purge the anonymized {@code PriceObservation}
     * rows a given receipt contributed to the community index. A normal receipt/account
     * delete cascades the {@code price_observation_audits} rows but deliberately keeps
     * the observations (LGPD: anonymized aggregates survive personal-data deletion), so
     * this is the only way to remove observations produced by a bad or test receipt.
     * The audit rows are deleted first (unique FK to the observation), then the
     * observations themselves. Returns how many observations were removed.
     */
    @Transactional
    public int purgeObservationsForReceipt(UUID receiptId) {
        var audits = observationAuditRepository.findByReceiptId(receiptId);
        if (audits.isEmpty()) {
            return 0;
        }
        var observationIds = audits.stream().map(audit -> audit.getObservation().getId()).toList();
        observationAuditRepository.deleteAll(audits);
        observationRepository.deleteAllById(observationIds);
        log.info("admin.purge_observations receipt={} removed={}", receiptId, observationIds.size());
        return observationIds.size();
    }

    /** Count observations with no audit row — leftovers from deleted accounts. A rising
     * number on dev means the nightly E2E purge is silently failing to clean up. */
    @Transactional(readOnly = true)
    public long countOrphanedObservations() {
        return observationRepository.countOrphaned();
    }

    /** Dev-only: bulk-delete every orphaned observation. Gated at the endpoint — on prod
     * these are LGPD-preserved anonymized aggregates. Returns how many were removed. */
    @Transactional
    public int deleteOrphanedObservations() {
        var removed = observationRepository.deleteOrphaned();
        log.info("admin.delete_orphaned_observations removed={}", removed);
        return removed;
    }
}
