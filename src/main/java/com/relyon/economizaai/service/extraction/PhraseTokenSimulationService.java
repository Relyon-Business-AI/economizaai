package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.dto.response.PhraseTokenSimulationResponse;
import com.relyon.economizaai.dto.response.PhraseTokenSimulationResponse.Row;
import com.relyon.economizaai.model.CategorizationBenchmarkEntry;
import com.relyon.economizaai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only simulator for the dictionary/brand phrase-window size
 * (max-phrase-tokens). Answers "what would happen if the window were N words?"
 * by re-running the lookup at each candidate N over the real unmatched backlog
 * (coverage) and the golden set (accuracy), without persisting anything or
 * touching the live cascade.
 *
 * <p>The knob only affects the dictionary and brand tiers, so coverage is
 * measured as a dictionary hit — this isolates the window's effect from the
 * EAN/alias paths that a full re-canonicalization would also exercise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhraseTokenSimulationService {

    private static final int MAX_EXAMPLES_PER_ROW = 8;
    private static final int MIN_TOKENS = 1;
    private static final int MAX_TOKENS = 8;

    @Value("${economizaai.categorization.max-phrase-tokens:3}")
    private int configuredTokens = 3;

    private final DictionaryClassifier dictionaryClassifier;
    private final BrandExtractor brandExtractor;
    private final ReceiptItemRepository receiptItemRepository;
    private final CategorizationBenchmarkEntryRepository benchmarkRepository;

    @Transactional(readOnly = true)
    public PhraseTokenSimulationResponse simulate(int minTokens, int maxTokens, int sampleSize) {
        var from = Math.max(MIN_TOKENS, minTokens);
        var to = Math.min(MAX_TOKENS, Math.max(from, maxTokens));

        var backlog = loadUnmatchedBacklog(sampleSize);
        var golden = benchmarkRepository.findAll();
        var totalItems = backlog.stream().mapToLong(DescriptionCount::occurrences).sum();

        var baselineCovered = coveredDescriptions(backlog, from);
        var rows = new ArrayList<Row>();
        for (var tokens = from; tokens <= to; tokens++) {
            rows.add(buildRow(tokens, from, backlog, golden, baselineCovered));
        }

        log.info("categorizer.phrase_token_simulation baseline={} range={}-{} backlog={} golden={}",
                configuredTokens, from, to, backlog.size(), golden.size());
        return new PhraseTokenSimulationResponse(
                configuredTokens, backlog.size(), totalItems, golden.size(), rows);
    }

    private Row buildRow(int tokens, int baselineTokens, List<DescriptionCount> backlog,
                         List<CategorizationBenchmarkEntry> golden,
                         Set<String> baselineCovered) {
        var distinctCovered = 0;
        var itemsRecovered = 0L;
        var newlyCovered = 0;
        var newItemsRecovered = 0L;
        var examples = new ArrayList<String>();
        for (var entry : backlog) {
            if (!isDictionaryHit(entry.description(), tokens)) continue;
            distinctCovered++;
            itemsRecovered += entry.occurrences();
            if (tokens != baselineTokens && !baselineCovered.contains(entry.description())) {
                newlyCovered++;
                newItemsRecovered += entry.occurrences();
                if (examples.size() < MAX_EXAMPLES_PER_ROW) examples.add(entry.description());
            }
        }

        var goldenCategoryCorrect = 0;
        var goldenBrandChecked = 0;
        var goldenBrandCorrect = 0;
        for (var row : golden) {
            var hit = dictionaryClassifier.classify(row.getDescription(), tokens);
            if (row.getExpectedCategory() != null && hit.category() == row.getExpectedCategory()) {
                goldenCategoryCorrect++;
            }
            if (row.getExpectedBrand() != null && !row.getExpectedBrand().isBlank()) {
                goldenBrandChecked++;
                var brand = hit.brand() != null ? hit.brand() : brandExtractor.find(row.getDescription(), tokens);
                if (brand != null && brand.equalsIgnoreCase(row.getExpectedBrand())) goldenBrandCorrect++;
            }
        }

        return new Row(tokens,
                distinctCovered, pct(distinctCovered, backlog.size()), itemsRecovered,
                newlyCovered, newItemsRecovered,
                goldenCategoryCorrect, pct(goldenCategoryCorrect, golden.size()),
                goldenBrandChecked, goldenBrandCorrect, pct(goldenBrandCorrect, goldenBrandChecked),
                examples);
    }

    private Set<String> coveredDescriptions(List<DescriptionCount> backlog, int tokens) {
        var covered = new LinkedHashSet<String>();
        for (var entry : backlog) {
            if (isDictionaryHit(entry.description(), tokens)) covered.add(entry.description());
        }
        return covered;
    }

    private boolean isDictionaryHit(String description, int tokens) {
        var hit = dictionaryClassifier.classify(description, tokens);
        return hit.category() != null || hit.genericName() != null;
    }

    private List<DescriptionCount> loadUnmatchedBacklog(int sampleSize) {
        var capped = Math.max(1, Math.min(sampleSize, 5000));
        return receiptItemRepository.topUnmatchedDescriptions(PageRequest.of(0, capped)).stream()
                .map(columns -> new DescriptionCount((String) columns[0], ((Number) columns[1]).longValue()))
                .toList();
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 0.0 : Math.round((part * 10000.0) / whole) / 100.0;
    }

    private record DescriptionCount(String description, long occurrences) {}
}
