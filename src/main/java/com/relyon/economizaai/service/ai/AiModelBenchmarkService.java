package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.AiModelBenchmarkRun;
import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiModelBenchmarkRunRepository;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

/**
 * Benchmark de ACURÁCIA por modelo: classifica uma amostra do golden set com um
 * modelo LLM explícito e compara a categoria à verdade. Mede qualidade PURA,
 * independente da revisão humana (complementa a taxa de aceitação do painel).
 *
 * <p>Assíncrono (452 itens no golden estouraria o cap diário) + amostragem
 * configurável. Cada chamada entra no ai_usage_log como MODEL_BENCHMARK com o
 * modelo e a latência — então o painel de comparação ganha custo/velocidade de
 * graça. Respeita o cap diário do {@link AiGateway} (aborta a rodada se bater).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelBenchmarkService {

    private final CategorizationBenchmarkEntryRepository goldenRepository;
    private final AiModelBenchmarkRunRepository runRepository;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${economizaai.ai.benchmark.default-sample:120}")
    private int defaultSample;

    private AiModelBenchmarkService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy AiModelBenchmarkService self) {
        this.self = self;
    }

    public UUID start(String model, Integer sampleSize) {
        if (!aiGateway.isEnabled()) {
            throw new AiGateway.AiUnavailableException("IA desabilitada: nenhum provedor configurado.");
        }
        if (runRepository.existsByStatus(AiModelBenchmarkRun.STATUS_RUNNING)) {
            throw new AiGateway.AiUnavailableException("Já existe um benchmark em execução.");
        }
        var goldenTotal = (int) goldenRepository.count();
        var sample = Math.max(1, Math.min(sampleSize == null ? defaultSample : sampleSize, goldenTotal));
        var run = runRepository.save(AiModelBenchmarkRun.builder()
                .model(model.trim())
                .provider(aiGateway.providerLabelForModel(model.trim()))
                .status(AiModelBenchmarkRun.STATUS_RUNNING)
                .sampleSize(sample)
                .build());
        self.run(run.getId(), model.trim(), sample);
        return run.getId();
    }

    @Async(AI_SWEEP_EXECUTOR)
    public void run(UUID runId, String model, int sample) {
        var rows = goldenRepository.findAll();
        Collections.shuffle(rows);
        var subset = rows.stream().limit(sample).toList();
        var correct = 0;
        var uncategorized = 0;
        var done = 0;
        try {
            for (var entry : subset) {
                var predicted = classifyOne(entry.getDescription(), model);
                if (predicted == entry.getExpectedCategory()) {
                    correct++;
                } else if (predicted == null) {
                    uncategorized++;
                }
                done++;
            }
            finish(runId, AiModelBenchmarkRun.STATUS_DONE, correct, uncategorized, done, null);
            log.info("ai_benchmark.done model={} sample={} correct={} acc={}%", model, done, correct,
                    done == 0 ? 0 : Math.round(1000.0 * correct / done) / 10.0);
        } catch (RuntimeException ex) {
            // Cap diário / crédito esgotado / provedor sem key: salva o parcial com o motivo.
            finish(runId, AiModelBenchmarkRun.STATUS_FAILED, correct, uncategorized, done,
                    abbreviate(ex.getMessage()));
            log.warn("ai_benchmark.failed model={} done={} reason={}", model, done, ex.getMessage());
        }
    }

    private ProductCategory classifyOne(String description, String model) {
        var categories = Arrays.stream(ProductCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
        var system = "Você interpreta descrições abreviadas de cupom fiscal (NFC-e) de supermercado/farmácia no Brasil. "
                + "Responda somente JSON válido, sem markdown.";
        var user = ("Item de cupom fiscal brasileiro: \"%s\"\n"
                + "Responda SOMENTE: {\"category\": \"<uma de: %s>\"}").formatted(description, categories);
        var text = aiGateway.complete(AiActivity.MODEL_BENCHMARK, model, system, user, 60);
        return parseCategory(text);
    }

    private ProductCategory parseCategory(String text) {
        try {
            var cleaned = text.trim().replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            var start = cleaned.indexOf('{');
            var end = cleaned.lastIndexOf('}');
            if (start < 0 || end < 0) return null;
            var node = objectMapper.readTree(cleaned.substring(start, end + 1));
            var category = node.path("category").asText(null);
            return category == null ? null : ProductCategory.valueOf(category.trim().toUpperCase());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null; // categoria ilegível/inválida conta como não-categorizado, não quebra a rodada
        }
    }

    @Transactional
    public void finish(UUID runId, String status, int correct, int uncategorized, int done, String error) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setCorrect(correct);
            run.setUncategorized(uncategorized);
            run.setAccuracyPct(done == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(100.0 * correct / done).setScale(1, RoundingMode.HALF_UP));
            run.setError(error);
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
        });
    }

    private static String abbreviate(String message) {
        if (message == null) return null;
        return message.length() > 490 ? message.substring(0, 490) : message;
    }
}
