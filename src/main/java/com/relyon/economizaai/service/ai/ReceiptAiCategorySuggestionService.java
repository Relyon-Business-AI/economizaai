package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.ClassificationInput;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.FineSuggestion;
import com.relyon.economizaai.service.extraction.ProductExtraction;
import com.relyon.economizaai.service.extraction.ProductExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

/**
 * A camada FINAL do cascade de categorização do scan: assim que a nota fica
 * PENDING_CONFIRMATION, roda em BACKGROUND o classificador de IA só pros itens que o
 * determinístico (EAN existente / dicionário / keyword) não resolveu, e grava a sugestão em
 * {@link ReceiptItem#getAiCategoryId()}. A tela de revisão mostra a sugestão (ajustável como
 * qualquer categoria) e o confirm a aplica ao criar o produto canônico (ver
 * {@code CanonicalizationService}). Best-effort por construção: IA fora do ar/cap estourado/
 * nota confirmada rápido demais → nada quebra, o job diário cobre o resto.
 *
 * <p>Pool-safe: leituras e escrita são chamadas curtas de repositório; a chamada de IA roda
 * fora de qualquer transação (regra do projeto).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptAiCategorySuggestionService {

    private static final Locale PT = Locale.forLanguageTag("pt");

    private final ReceiptRepository receiptRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ProductRepository productRepository;
    private final ProductExtractor productExtractor;
    private final AiFineCategoryClassifier classifier;
    private final CategoryResolver categoryResolver;

    /** Mesmo piso do sweep de admin pra PREENCHER subcategoria vazia (ação SET). */
    @Value("${economizaai.ai.subcategory-suggestion.set-min-confidence:0.55}")
    private double setMinConfidence = 0.55;

    public boolean isEnabled() {
        return classifier.isEnabled();
    }

    /** Entrada async (despachada pós-commit da ingestão). Nunca propaga falha — é best-effort. */
    @Async(AI_SWEEP_EXECUTOR)
    public void suggestForReceiptAsync(UUID receiptId) {
        try {
            suggestForReceipt(receiptId);
        } catch (RuntimeException ex) {
            log.warn("receipt.ai_suggest.failed receipt={} reason={}", receiptId, ex.getMessage());
        }
    }

    /** Síncrono (testável): classifica os itens não resolvidos e grava as sugestões. */
    public void suggestForReceipt(UUID receiptId) {
        if (!classifier.isEnabled()) return;
        if (!isPendingConfirmation(receiptId)) return;

        var items = receiptItemRepository.findByReceiptId(receiptId);
        var candidates = new ArrayList<ReceiptItem>();
        var extractionByItem = new HashMap<UUID, ProductExtraction>();
        for (var item : items) {
            if (item.isExcluded() || item.getAiCategoryId() != null) continue;
            if (linksToExistingProduct(item)) continue; // confirm vai LINKAR, não criar — produto já tem (ou ganha no job diário)
            var extraction = productExtractor.extract(item.getRawDescription());
            if (extraction.categoryId() != null && categoryResolver.isLeafNode(extraction.categoryId())) {
                continue; // determinístico já deu a subcategoria — IA não entra
            }
            candidates.add(item);
            extractionByItem.put(item.getId(), extraction);
        }
        if (candidates.isEmpty()) {
            log.info("receipt.ai_suggest.none receipt={} items={}", receiptId, items.size());
            return;
        }

        // Chamada de IA FORA de transação (uma por nota; o gabarito fixo é cacheado pelo provedor).
        List<FineSuggestion> suggestions;
        try {
            suggestions = classifier.suggest(buildInputs(candidates, extractionByItem));
        } catch (AiGateway.AiUnavailableException unavailable) {
            log.warn("receipt.ai_suggest.unavailable receipt={} reason={}", receiptId, unavailable.getMessage());
            return;
        }

        // Nota confirmada enquanto a IA pensava → produtos já criados; o job diário cobre.
        if (!isPendingConfirmation(receiptId)) {
            log.info("receipt.ai_suggest.skipped_confirmed receipt={}", receiptId);
            return;
        }
        var applied = applySuggestions(candidates, extractionByItem, suggestions);
        log.info("receipt.ai_suggest done receipt={} candidates={} suggested={} applied={}",
                receiptId, candidates.size(), suggestions.size(), applied);
    }

    private boolean isPendingConfirmation(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .map(receipt -> receipt.getStatus() == ReceiptStatus.PENDING_CONFIRMATION)
                .orElse(false);
    }

    /** Item com EAN de produto já conhecido vai ser LINKADO no confirm — não gastamos IA com ele. */
    private boolean linksToExistingProduct(ReceiptItem item) {
        return item.getEan() != null && !item.getEan().isBlank()
                && productRepository.findByEan(item.getEan()).isPresent();
    }

    private List<ClassificationInput> buildInputs(List<ReceiptItem> candidates,
                                                  HashMap<UUID, ProductExtraction> extractionByItem) {
        return candidates.stream().map(item -> {
            var extraction = extractionByItem.get(item.getId());
            var currentCategory = extraction.category() != null
                    ? categoryResolver.labelFor(categoryResolver.idForLegacyEnum(extraction.category()), PT)
                    : null;
            return new ClassificationInput(item.getId(), item.getRawDescription(),
                    DescriptionNormalizer.normalize(item.getRawDescription()), extraction.brand(),
                    currentCategory, null);
        }).toList();
    }

    private int applySuggestions(List<ReceiptItem> candidates,
                                 HashMap<UUID, ProductExtraction> extractionByItem,
                                 List<FineSuggestion> suggestions) {
        var byId = new HashMap<UUID, ReceiptItem>();
        candidates.forEach(item -> byId.put(item.getId(), item));
        var toSave = new ArrayList<ReceiptItem>();
        for (var suggestion : suggestions) {
            var item = byId.get(suggestion.productId()); // o id ecoado é o do ITEM neste fluxo
            if (item == null) continue;
            if (suggestion.confidence() < setMinConfidence) continue;
            var extraction = extractionByItem.get(item.getId());
            // A IA nunca contradiz o determinístico: com categoria do dicionário presente, só
            // aceita subcategoria DENTRO dela.
            if (extraction.category() != null) {
                var dictGroup = categoryResolver.idForLegacyEnum(extraction.category());
                if (dictGroup != null && !dictGroup.equals(categoryResolver.groupIdOf(suggestion.categoryId()))) {
                    continue;
                }
            }
            item.setAiCategoryId(suggestion.categoryId());
            toSave.add(item);
        }
        receiptItemRepository.saveAll(toSave);
        return toSave.size();
    }
}
