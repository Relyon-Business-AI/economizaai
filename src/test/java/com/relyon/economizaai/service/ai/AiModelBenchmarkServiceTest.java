package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiModelBenchmarkRun;
import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiModelBenchmarkRunRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelBenchmarkServiceTest {

    @Mock private CategorizationBenchmarkEntryRepository goldenRepository;
    @Mock private AiModelBenchmarkRunRepository runRepository;
    @Mock private AiGateway aiGateway;

    private AiModelBenchmarkService service;

    @BeforeEach
    void setUp() {
        service = new AiModelBenchmarkService(goldenRepository, runRepository, aiGateway);
        service.setSelf(service);
    }

    private CategorizationBenchmarkEntry golden(String desc, ProductCategory expected) {
        var entry = new CategorizationBenchmarkEntry();
        entry.setDescription(desc);
        entry.setExpectedCategory(expected);
        return entry;
    }

    @Test
    void run_scoresAccuracyAgainstGolden_countsUnparseableAsUncategorized() {
        var runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(java.util.Optional.of(
                AiModelBenchmarkRun.builder().model("gpt-5").provider("OPENAI")
                        .status(AiModelBenchmarkRun.STATUS_RUNNING).sampleSize(3).build()));
        when(goldenRepository.findAll()).thenReturn(new java.util.ArrayList<>(List.of(
                golden("ARROZ TIO JOAO 1KG", ProductCategory.GROCERIES),
                golden("DETERGENTE YPE 500ML", ProductCategory.CLEANING),
                golden("LEITE ITALAC 1L", ProductCategory.MEAT_DAIRY))));
        // 1 certo, 1 errado, 1 ilegível (null → uncategorized)
        when(aiGateway.complete(eq(AiActivity.MODEL_BENCHMARK), eq("gpt-5"), anyString(), anyString(), anyInt()))
                .thenReturn("{\"category\": \"GROCERIES\"}")   // vai casar com algum item
                .thenReturn("{\"category\": \"BAKERY\"}")       // erra
                .thenReturn("sem json aqui");                   // ilegível

        service.run(runId, "gpt-5", 3);

        var captor = ArgumentCaptor.forClass(AiModelBenchmarkRun.class);
        verify(runRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(AiModelBenchmarkRun.STATUS_DONE, saved.getStatus());
        assertEquals(3, saved.getCorrect() + saved.getUncategorized() + wrongOf(saved));
        assertEquals(1, saved.getUncategorized());
    }

    private int wrongOf(AiModelBenchmarkRun run) {
        return run.getSampleSize() - run.getCorrect() - run.getUncategorized();
    }

    @Test
    void run_whenCapHitMidway_savesPartialAsFailed() {
        var runId = UUID.randomUUID();
        lenient().when(runRepository.findById(runId)).thenReturn(java.util.Optional.of(
                AiModelBenchmarkRun.builder().model("gpt-5").provider("OPENAI")
                        .status(AiModelBenchmarkRun.STATUS_RUNNING).sampleSize(2).build()));
        when(goldenRepository.findAll()).thenReturn(new java.util.ArrayList<>(List.of(
                golden("ARROZ 1KG", ProductCategory.GROCERIES),
                golden("SABAO 1KG", ProductCategory.CLEANING))));
        when(aiGateway.complete(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"category\": \"GROCERIES\"}")
                .thenThrow(new AiGateway.AiUnavailableException("Cap diário atingido"));

        service.run(runId, "gpt-5", 2);

        var captor = ArgumentCaptor.forClass(AiModelBenchmarkRun.class);
        verify(runRepository).save(captor.capture());
        assertEquals(AiModelBenchmarkRun.STATUS_FAILED, captor.getValue().getStatus());
    }
}
