package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.dto.request.MergeProductRequest;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.admin.AdminProductService;
import com.relyon.economizaai.service.extraction.CategorizerAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiFindingServiceTest {

    @Mock private AiFindingRepository findingRepository;
    @Mock private CategorizerAdminService categorizerAdminService;
    @Mock private AdminProductService adminProductService;
    @Mock private ProductRepository productRepository;

    private AiFindingService service;

    @BeforeEach
    void setUp() {
        service = new AiFindingService(findingRepository, categorizerAdminService,
                adminProductService, productRepository);
        lenient().when(findingRepository.save(any(AiFinding.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AiFinding pending(AiFindingType type, String payload) {
        var finding = AiFinding.builder()
                .id(UUID.randomUUID()).sweepRunId(UUID.randomUUID())
                .type(type).status(AiFindingStatus.PENDING)
                .title("t").payload(payload).build();
        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        return finding;
    }

    @Test
    void approveRuleRoutesToCuratedImport() {
        var finding = pending(AiFindingType.MISSING_RULE,
                "{\"keyword\":\"shamp\",\"genericName\":\"Shampoo\",\"brand\":null,\"category\":\"PERSONAL_CARE\"}");
        when(categorizerAdminService.importCuratedEntries(anyList()))
                .thenReturn(new CategorizerAdminService.CuratedImportOutcome(1, 0, 3));

        var approved = service.approve(finding.getId());

        assertEquals(AiFindingStatus.APPROVED, approved.getStatus());
        var captor = ArgumentCaptor.forClass(List.class);
        verify(categorizerAdminService).importCuratedEntries(captor.capture());
        var request = (CategorizerAdminService.CuratedImportRequest) captor.getValue().get(0);
        assertEquals("shamp", request.keyword());
        assertEquals(ProductCategory.PERSONAL_CARE, request.category());
    }

    @Test
    void approveDuplicateRoutesToMerge() {
        var survivor = UUID.randomUUID();
        var absorbed = UUID.randomUUID();
        var finding = pending(AiFindingType.DUPLICATE,
                "{\"survivorId\":\"" + survivor + "\",\"absorbedId\":\"" + absorbed + "\"}");

        service.approve(finding.getId());

        verify(adminProductService).merge(eq(survivor), any(MergeProductRequest.class));
    }

    @Test
    void approveFriendlyNameSetsGenericName() {
        var productId = UUID.randomUUID();
        var product = Product.builder().id(productId).normalizedName("P.BOLACHA MILHO kg").build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        var finding = pending(AiFindingType.FRIENDLY_NAME,
                "{\"productId\":\"" + productId + "\",\"genericName\":\"Bolacha de Milho\"}");

        service.approve(finding.getId());

        assertEquals("Bolacha de Milho", product.getGenericName());
        assertEquals("bolacha de milho", product.getGenericNameNorm());
    }

    @Test
    void informationalTypesJustAcknowledge() {
        var finding = pending(AiFindingType.ANOMALY, "{\"description\":\"x\"}");
        var approved = service.approve(finding.getId());
        assertEquals(AiFindingStatus.APPROVED, approved.getStatus());
    }

    @Test
    void alreadyReviewedThrows() {
        var finding = AiFinding.builder()
                .id(UUID.randomUUID()).type(AiFindingType.ANOMALY)
                .status(AiFindingStatus.REJECTED).title("t").payload("{}").build();
        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        assertThrows(IllegalStateException.class, () -> service.approve(finding.getId()));
    }

    @Test
    void bulkApproveIsPartialFailureTolerant() {
        var ok = pending(AiFindingType.ANOMALY, "{}");
        var missing = UUID.randomUUID();
        when(findingRepository.findById(missing)).thenReturn(Optional.empty());

        var outcome = service.approveBulk(List.of(ok.getId(), missing));

        assertEquals(1, outcome.approved());
        assertEquals(1, outcome.failed());
    }
}
