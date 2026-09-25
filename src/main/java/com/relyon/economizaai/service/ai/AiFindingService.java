package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.request.MergeProductRequest;
import com.relyon.economizaai.dto.request.SetProductBrandRequest;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.admin.AdminProductService;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.CategorizerAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Human review of AI findings. Approving APPLIES the proposal by routing it to
 * the SAME admin services a human would use (curated import — which already
 * re-canonicalizes orphans —, brand import, product patch, merge). Informational
 * types (consensus/merchant/anomaly) just get acknowledged. Rejecting keeps the
 * row so the sweep never re-proposes the same thing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFindingService {

    private final AiFindingRepository findingRepository;
    private final CategorizerAdminService categorizerAdminService;
    private final AdminProductService adminProductService;
    private final ProductRepository productRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public Page<AiFinding> list(AiFindingStatus status, AiFindingType type, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return type == null
                ? findingRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : findingRepository.findByStatusAndTypeOrderByCreatedAtDesc(status, type, pageable);
    }

    @Transactional
    public AiFinding approve(UUID id) {
        var finding = loadPending(id);
        apply(finding);
        finding.setStatus(AiFindingStatus.APPROVED);
        finding.setAppliedAt(LocalDateTime.now());
        log.info("ai.finding.approved id={} type={}", id, finding.getType());
        return findingRepository.save(finding);
    }

    @Transactional
    public AiFinding reject(UUID id) {
        var finding = loadPending(id);
        finding.setStatus(AiFindingStatus.REJECTED);
        log.info("ai.finding.rejected id={} type={}", id, finding.getType());
        return findingRepository.save(finding);
    }

    /** Applies each id independently — one failure doesn't roll back the batch. */
    public BulkOutcome approveBulk(List<UUID> ids) {
        var approved = 0;
        var failed = 0;
        for (var id : ids) {
            try {
                approve(id);
                approved++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("ai.finding.bulk_approve_failed id={} reason={}", id, ex.getMessage());
            }
        }
        return new BulkOutcome(approved, failed);
    }

    private AiFinding loadPending(UUID id) {
        var finding = findingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Achado não encontrado: " + id));
        if (finding.getStatus() != AiFindingStatus.PENDING) {
            throw new IllegalStateException("Achado já revisado (" + finding.getStatus() + ")");
        }
        return finding;
    }

    private void apply(AiFinding finding) {
        var payload = parsePayload(finding.getPayload());
        switch (finding.getType()) {
            case MISSING_RULE -> applyRule(payload);
            case MISSING_BRAND -> applyBrand(payload);
            case SUSPECT_CATEGORY -> applyCategory(payload);
            case DUPLICATE -> applyMerge(payload);
            case FRIENDLY_NAME -> applyFriendlyName(payload);
            // Informational — approval = acknowledged; resolution is manual/elsewhere.
            case CONSENSUS_REVIEW, MERCHANT_REVIEW, ANOMALY -> { }
        }
    }

    private void applyRule(JsonNode payload) {
        var brand = payload.path("brand").isNull() ? null : payload.path("brand").asText(null);
        var request = new CategorizerAdminService.CuratedImportRequest(
                payload.path("keyword").asText(),
                blankToNull(payload.path("genericName").asText(null)),
                blankToNull(brand),
                ProductCategory.valueOf(payload.path("category").asText()));
        var outcome = categorizerAdminService.importCuratedEntries(List.of(request));
        log.info("ai.apply.rule keyword='{}' recanonicalized={}", request.keyword(), outcome.recanonicalized());
    }

    private void applyBrand(JsonNode payload) {
        var key = payload.path("brandKey").asText();
        var display = payload.path("brandDisplay").asText();
        categorizerAdminService.importBrands(List.of(
                new CategorizerAdminService.BrandImportRequest(key, display)));
        // Also fix the product that motivated the finding, when present.
        var productId = payload.path("productId").asText("");
        if (!productId.isBlank()) {
            adminProductService.setBrand(UUID.fromString(productId), new SetProductBrandRequest(display));
        }
    }

    private void applyCategory(JsonNode payload) {
        adminProductService.setCategory(
                UUID.fromString(payload.path("productId").asText()),
                ProductCategory.valueOf(payload.path("category").asText()));
    }

    private void applyMerge(JsonNode payload) {
        adminProductService.merge(
                UUID.fromString(payload.path("survivorId").asText()),
                new MergeProductRequest(UUID.fromString(payload.path("absorbedId").asText()), null));
    }

    private void applyFriendlyName(JsonNode payload) {
        var product = productRepository.findById(UUID.fromString(payload.path("productId").asText()))
                .orElseThrow(() -> new IllegalArgumentException("Produto do achado não existe mais"));
        var name = payload.path("genericName").asText();
        product.setGenericName(name);
        product.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(name));
        productRepository.save(product);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Payload do achado inválido: " + ex.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    public record BulkOutcome(int approved, int failed) {}
}
