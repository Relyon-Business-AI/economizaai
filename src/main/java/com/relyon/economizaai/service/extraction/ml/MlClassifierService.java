package com.relyon.economizaai.service.extraction.ml;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * Owns the two trained classifiers (category, genericName) and exposes
 * predict() to the rest of the app. Trains on startup and on a periodic
 * schedule. Training data: only Products whose categorizationSource is
 * DICTIONARY or USER — LEARNED_DICTIONARY is excluded because those products
 * were themselves classified via tokens auto-promoted from ML predictions;
 * including them would create a feedback loop (ML → learned_dict → product →
 * ML training → amplified ML output).
 *
 * Models live in memory only. Cheap to retrain (NB + ~1k examples is
 * sub-second), so persisting them isn't worth the complexity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlClassifierService {

    private static final EnumSet<CategorizationSource> TRUSTED_SOURCES = EnumSet.of(
            CategorizationSource.DICTIONARY,
            CategorizationSource.USER,
            CategorizationSource.CONSENSUS
            // LEARNED_DICTIONARY intentionally excluded — it's ML-once-removed (feedback loop risk)
    );
    private static final int MIN_TRAINING_SIZE = 30;

    private final ProductRepository productRepository;

    // Self-reference so the scheduled trigger's call to @Transactional retrain()
    // goes through the Spring proxy. Defaults to `this` for plain unit tests.
    @Lazy
    @Autowired
    private MlClassifierService self = this;

    @Value("${economizaai.ml.confidence-threshold:0.75}")
    private double confidenceThreshold;

    /**
     * When false, the cascade does NOT apply ML predictions to products — but
     * the model is still trained and still measurable (shadow) via the
     * benchmark. Lets us bench an unreliable model without losing the training
     * loop, and flip it back on once the benchmark says it's good enough.
     */
    @Value("${economizaai.ml.category-apply-enabled:false}")
    private boolean categoryApplyEnabled;

    private final MultinomialNaiveBayes<ProductCategory> categoryModel = new MultinomialNaiveBayes<>();
    private final MultinomialNaiveBayes<String> genericNameModel = new MultinomialNaiveBayes<>();
    private volatile boolean ready = false;
    // Instant (not LocalDateTime): serializa com offset 'Z' pro FE converter pra fuso local.
    // LocalDateTime.now() gravava a hora do servidor (UTC) sem zona e o FE mostrava a hora UTC.
    private volatile Instant lastTrainedAt;

    @PostConstruct
    void trainOnStartup() {
        // INTENTIONALLY this.retrain(), NOT self.retrain() (Sonar S6809 is a false
        // positive here): during @PostConstruct the bean is still being created, so
        // calling through the @Lazy self proxy triggers BeanCurrentlyInCreation
        // (circular reference) and the context fails to start. The proxy is only
        // safe AFTER construction (see scheduledRetrain). The startup transaction
        // isn't critical - findAll() works fine without the read-only optimization.
        retrain();
    }

    @Scheduled(fixedDelayString = "${economizaai.ml.retrain-interval-ms:604800000}",
               initialDelayString = "${economizaai.ml.retrain-initial-delay-ms:604800000}")
    public void scheduledRetrain() {
        log.info("ml.retrain.scheduled");
        self.retrain();
    }

    @Transactional(readOnly = true)
    public synchronized RetrainOutcome retrain() {
        ready = false; // gate concurrent predictions while both models are being rebuilt
        var started = System.nanoTime();
        var trustedProducts = productRepository.findByCategorizationSourceIn(TRUSTED_SOURCES).stream()
                .toList();
        var categoryExamples = trustedProducts.stream()
                .filter(p -> p.getCategory() != null)
                .map(p -> new MultinomialNaiveBayes.Example<>(p.getNormalizedName(), p.getCategory()))
                .toList();
        var genericNameExamples = trustedProducts.stream()
                .filter(p -> p.getGenericName() != null)
                .map(p -> new MultinomialNaiveBayes.Example<>(p.getNormalizedName(), p.getGenericName()))
                .toList();

        if (categoryExamples.size() < MIN_TRAINING_SIZE) {
            log.info("ml.retrain.skipped reason=insufficient-data trustedProducts={} categoryExamples={} minRequired={}",
                    trustedProducts.size(), categoryExamples.size(), MIN_TRAINING_SIZE);
            ready = false;
            return new RetrainOutcome(false, categoryExamples.size(), genericNameExamples.size(), Duration.ZERO);
        }

        categoryModel.train(categoryExamples, CharNGramFeatureExtractor::extract);
        genericNameModel.train(genericNameExamples, CharNGramFeatureExtractor::extract);
        ready = true;
        lastTrainedAt = Instant.now();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        log.info("ml.retrain.done categoryExamples={} categoryLabels={} genericExamples={} genericLabels={} vocab={} elapsedMs={}",
                categoryExamples.size(), categoryModel.labelCount(),
                genericNameExamples.size(), genericNameModel.labelCount(),
                categoryModel.vocabularySize(), elapsed.toMillis());
        return new RetrainOutcome(true, categoryExamples.size(), genericNameExamples.size(), elapsed);
    }

    public MlPrediction<ProductCategory> predictCategory(String text) {
        if (!ready) return MlPrediction.empty();
        return categoryModel.predict(CharNGramFeatureExtractor.extract(text));
    }

    public MlPrediction<String> predictGenericName(String text) {
        if (!ready) return MlPrediction.empty();
        return genericNameModel.predict(CharNGramFeatureExtractor.extract(text));
    }

    public boolean isReady() {
        return ready;
    }

    /** Whether the cascade should apply ML predictions (vs. train/measure only). */
    public boolean isCategoryApplyEnabled() {
        return categoryApplyEnabled;
    }

    public Instant getLastTrainedAt() {
        return lastTrainedAt;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public record RetrainOutcome(boolean trained, int categoryExamples, int genericNameExamples, Duration elapsed) {}
}
