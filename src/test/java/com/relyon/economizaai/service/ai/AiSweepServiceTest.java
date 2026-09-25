package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.AiSweepRun;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.AiSweepRunRepository;
import com.relyon.economizaai.repository.ConsensusGraduationAuditRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.admin.AdminProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSweepServiceTest {

    @Mock private AiGateway aiGateway;
    @Mock private AiFindingRepository findingRepository;
    @Mock private AiSweepRunRepository sweepRunRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private ConsensusGraduationAuditRepository consensusAuditRepository;
    @Mock private AdminProductService adminProductService;

    private AiSweepService service;

    @BeforeEach
    void setUp() {
        service = new AiSweepService(aiGateway, findingRepository, sweepRunRepository,
                receiptItemRepository, productRepository, receiptRepository,
                consensusAuditRepository, adminProductService);
    }

    @Test
    void startSweepRefusesWhenDisabled() {
        when(aiGateway.isEnabled()).thenReturn(false);
        assertThrows(AiGateway.AiUnavailableException.class, () -> service.startSweep());
    }

    @Test
    void startSweepRefusesWhenAlreadyRunning() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(sweepRunRepository.existsByStatus(AiSweepRun.STATUS_RUNNING)).thenReturn(true);
        assertThrows(AiGateway.AiUnavailableException.class, () -> service.startSweep());
    }

    @Test
    void parseArrayHandlesCodeFencesAndGarbage() {
        assertEquals(2, service.parseArray("```json\n[{\"a\":1},{\"a\":2}]\n```").size());
        assertEquals(1, service.parseArray("blah blah [{\"keyword\":\"x\"}] trailing").size());
        assertTrue(service.parseArray("no json here").isEmpty());
        assertTrue(service.parseArray(null).isEmpty());
        assertTrue(service.parseArray("{\"not\":\"array\"}").isEmpty());
    }

    @Test
    void runSweepWithEmptyDataProducesZeroFindingsAndFinishes() {
        // All inputs empty → no AI calls, run ends DONE with 0 findings.
        var run = AiSweepRun.builder().id(UUID.randomUUID()).status(AiSweepRun.STATUS_RUNNING).findings(0).build();
        when(sweepRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(receiptItemRepository.topUnmatchedDescriptions(any())).thenReturn(List.of());
        when(adminProductService.listDuplicateGroups()).thenReturn(List.of());
        when(consensusAuditRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(receiptRepository.findDistinctMerchants(any())).thenReturn(List.of());
        when(receiptItemRepository.findRecentConfirmedWithReceipt(any())).thenReturn(List.of());
        lenient().when(sweepRunRepository.save(any(AiSweepRun.class))).thenAnswer(inv -> inv.getArgument(0));

        service.runSweep(run.getId());

        assertEquals(AiSweepRun.STATUS_DONE, run.getStatus());
        assertEquals(0, run.getFindings());
    }

    @Test
    void missingRuleFindingIsSavedFromGatewayResponse() {
        var run = AiSweepRun.builder().id(UUID.randomUUID()).status(AiSweepRun.STATUS_RUNNING).findings(0).build();
        when(sweepRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        lenient().when(sweepRunRepository.save(any(AiSweepRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptItemRepository.topUnmatchedDescriptions(any()))
                .thenReturn(List.<Object[]>of(new Object[]{"p.palito salgado kg", 9L}));
        // Other modules empty (paginated methods default to empty list):
        when(adminProductService.listDuplicateGroups()).thenReturn(List.of());
        when(consensusAuditRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(receiptRepository.findDistinctMerchants(any())).thenReturn(List.of());
        when(receiptItemRepository.findRecentConfirmedWithReceipt(any())).thenReturn(List.of());

        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(aiGateway.complete(any(), any(), any(), any(), anyInt()))
                .thenReturn("[{\"description\":\"p.palito salgado kg\",\"keyword\":\"palito salgado\"," +
                        "\"genericName\":\"Palito Salgado\",\"brand\":null,\"category\":\"BAKERY\"," +
                        "\"confidence\":0.9,\"reason\":\"padaria\"}]");
        when(findingRepository.existsByTypeAndTitle(any(), any())).thenReturn(false);
        when(findingRepository.save(any(AiFinding.class))).thenAnswer(inv -> inv.getArgument(0));

        service.runSweep(run.getId());

        assertEquals(AiSweepRun.STATUS_DONE, run.getStatus());
        assertEquals(1, run.getFindings());
    }

    @Test
    void creditExhaustionAbortsRemainingModulesButKeepsRun() {
        // 1º módulo estoura crédito → módulos restantes NÃO são chamados
        // (verificado por só 1 chamada ao gateway) e a run termina FAILED com o motivo.
        var run = AiSweepRun.builder().id(UUID.randomUUID()).status(AiSweepRun.STATUS_RUNNING).findings(0).build();
        when(sweepRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        lenient().when(sweepRunRepository.save(any(AiSweepRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptItemRepository.topUnmatchedDescriptions(any()))
                .thenReturn(List.<Object[]>of(new Object[]{"x", 1L}));
        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(aiGateway.complete(any(), any(), any(), any(), anyInt()))
                .thenThrow(new AiGateway.AiUnavailableException("Créditos da API de IA esgotados — fila coberta na próxima varredura."));

        service.runSweep(run.getId());

        assertEquals(AiSweepRun.STATUS_FAILED, run.getStatus());
        assertTrue(run.getError().contains("Créditos"));
        verify(aiGateway, times(1))
                .complete(any(), any(), any(), any(), anyInt());
    }

    @Test
    void duplicateTitleIsNotReProposed() {
        var run = AiSweepRun.builder().id(UUID.randomUUID()).status(AiSweepRun.STATUS_RUNNING).findings(0).build();
        when(sweepRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        lenient().when(sweepRunRepository.save(any(AiSweepRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptItemRepository.topUnmatchedDescriptions(any()))
                .thenReturn(List.<Object[]>of(new Object[]{"x", 1L}));
        when(adminProductService.listDuplicateGroups()).thenReturn(List.of());
        when(consensusAuditRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(receiptRepository.findDistinctMerchants(any())).thenReturn(List.of());
        when(receiptItemRepository.findRecentConfirmedWithReceipt(any())).thenReturn(List.of());
        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(aiGateway.complete(any(), any(), any(), any(), anyInt()))
                .thenReturn("[{\"description\":\"x\",\"keyword\":\"xis\",\"genericName\":\"Xis\"," +
                        "\"category\":\"GROCERIES\",\"confidence\":0.8}]");
        when(findingRepository.existsByTypeAndTitle(any(AiFindingType.class), any())).thenReturn(true);

        service.runSweep(run.getId());

        assertEquals(0, run.getFindings());
    }

}
