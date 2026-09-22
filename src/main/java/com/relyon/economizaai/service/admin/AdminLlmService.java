package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.LlmReportResponse;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.LlmDisagreementRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin view of the LLM teacher layer: what it labeled, what it cost, how
 * often humans overrode it, and the open disagreement queue. Resolving a
 * disagreement is the ONLY path by which an LLM suggestion overrules a
 * higher-ranked source — and it lands as source USER (a human decided).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLlmService {

    private final ProductRepository productRepository;
    private final LlmDisagreementRepository disagreementRepository;
    private final CuratedDictionaryEntryRepository curatedRepository;
    private final HouseholdProductCategoryOverrideRepository overrideRepository;
    private final PaidApiCallRepository paidApiCallRepository;

    @Transactional(readOnly = true)
    public LlmReportResponse report(int enrichmentMaxAttempts) {
        var llmLabeled = productRepository.countByCategorizationSource(CategorizationSource.LLM);
        var overrides = overrideRepository.countOverridesOnLlmLabeledProducts();
        var overrideRate = llmLabeled == 0 ? 0.0 : (double) overrides / llmLabeled;
        var disagreements = disagreementRepository.findTop100ByResolvedAtIsNullOrderByCreatedAtDesc().stream()
                .map(disagreement -> new LlmReportResponse.OpenDisagreement(
                        disagreement.getId(),
                        disagreement.getProduct().getId(),
                        disagreement.getProduct().getNormalizedName(),
                        disagreement.getField(),
                        disagreement.getCurrentValue(),
                        disagreement.getCurrentSource(),
                        disagreement.getSuggestedValue(),
                        disagreement.getConfidence(),
                        disagreement.getCreatedAt()))
                .toList();
        return new LlmReportResponse(
                llmLabeled,
                productRepository.countEnrichmentCandidates(enrichmentMaxAttempts),
                curatedRepository.countByOrigin("LLM"),
                overrides,
                overrideRate,
                paidApiCallRepository.countByServiceAndCreatedAtGreaterThanEqual(
                        PaidApiService.LLM_ENRICH, startOfTodayUtc()),
                paidApiCallRepository.countByServiceAndCreatedAtGreaterThanEqual(
                        PaidApiService.LLM_VISION, startOfTodayUtc()),
                disagreements);
    }

    /**
     * ACCEPT applies the suggestion as a HUMAN decision (source USER — it now
     * outranks everything the machines say); REJECT just closes the row.
     */
    @Transactional
    public void resolveDisagreement(UUID disagreementId, boolean accept) {
        var disagreement = disagreementRepository.findById(disagreementId)
                .orElseThrow(ProductNotFoundException::new);
        if (accept) {
            var product = disagreement.getProduct();
            if ("category".equals(disagreement.getField()) && disagreement.getSuggestedValue() != null) {
                product.setCategory(ProductCategory.valueOf(
                        disagreement.getSuggestedValue().trim().toUpperCase(Locale.ROOT)));
                product.setCategorizationSource(CategorizationSource.USER);
            } else if ("brand".equals(disagreement.getField())) {
                product.setBrand(disagreement.getSuggestedValue());
            }
            productRepository.save(product);
        }
        disagreement.setResolvedAt(LocalDateTime.now());
        disagreementRepository.save(disagreement);
        log.info("llm_disagreement.resolved id={} accepted={}", disagreementId, accept);
    }

    private static OffsetDateTime startOfTodayUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
