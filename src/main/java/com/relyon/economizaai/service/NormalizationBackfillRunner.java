package com.relyon.economizaai.service;

import com.relyon.economizaai.model.BrandRegistryEntry;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import com.relyon.economizaai.service.extraction.DictionaryClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One-time-ish (idempotent) startup backfill that re-normalizes the match keys /
 * mirror columns that predate the app-wide normalization standard, so existing
 * data matches the same way new data does:
 *
 * <ul>
 *   <li>dictionary/brand keys stored with a lighter {@code toLowerCase} (accents
 *       kept) are re-normalized to the {@link DescriptionNormalizer} form;</li>
 *   <li>products get their {@code genericNameNorm}/{@code brandNorm} mirrors filled.</li>
 * </ul>
 *
 * Idempotent: re-normalizing an already-normalized value is a no-op, so this is
 * safe to run on every boot. Non-destructive: on a key collision (another row
 * already holds the normalized key) it logs and SKIPS rather than deleting —
 * leaving the duplicate for manual resolution. Never blocks startup.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class NormalizationBackfillRunner implements ApplicationRunner {

    private final CuratedDictionaryEntryRepository curatedRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final BrandRegistryEntryRepository brandRepository;
    private final ProductRepository productRepository;
    private final DictionaryClassifier dictionaryClassifier;
    private final BrandExtractor brandExtractor;

    @Override
    public void run(ApplicationArguments args) {
        var curatedUpdated = backfillCuratedKeys();
        backfillLearnedKeys();
        var brandUpdated = backfillBrandKeys();
        backfillProductNorms();
        // Refresh the in-memory snapshots so the re-normalized keys take effect now
        // (not only after the next restart / reload trigger).
        if (curatedUpdated > 0) dictionaryClassifier.reloadCuratedEntries();
        if (brandUpdated > 0) brandExtractor.reload();
    }

    protected int backfillCuratedKeys() {
        return backfillKeys("curated", curatedRepository::findAll,
                CuratedDictionaryEntry::getKeyword,
                (entry, key) -> entry.setKeyword(key),
                key -> curatedRepository.findByKeyword(key).isPresent(),
                curatedRepository::save);
    }

    protected int backfillLearnedKeys() {
        return backfillKeys("learned", learnedRepository::findAll,
                LearnedDictionaryEntry::getNormalizedToken,
                (entry, key) -> entry.setNormalizedToken(key),
                key -> learnedRepository.findByNormalizedToken(key).isPresent(),
                learnedRepository::save);
    }

    protected int backfillBrandKeys() {
        return backfillKeys("brand", brandRepository::findAll,
                BrandRegistryEntry::getNormalizedKey,
                (entry, key) -> entry.setNormalizedKey(key),
                key -> brandRepository.findByNormalizedKey(key).isPresent(),
                brandRepository::save);
    }

    // rows come from a Supplier (not a materialized List) so the fetch itself runs
    // INSIDE the try — a failing findAll() must never escape and block startup.
    private <T> int backfillKeys(String table, Supplier<List<T>> rowsSupplier,
                                 Function<T, String> getKey,
                                 BiConsumer<T, String> setKey,
                                 Function<String, Boolean> keyExists,
                                 Consumer<T> save) {
        var updated = 0;
        var collisions = 0;
        try {
            for (var row : rowsSupplier.get()) {
                var current = getKey.apply(row);
                var normalized = DescriptionNormalizer.normalize(current);
                if (normalized.isBlank() || normalized.equals(current)) continue;
                if (keyExists.apply(normalized)) {
                    collisions++;
                    log.warn("normalization.backfill.collision table={} key='{}' -> '{}'", table, current, normalized);
                    continue;
                }
                setKey.accept(row, normalized);
                save.accept(row);
                updated++;
            }
            log.info("normalization.backfill table={} updated={} collisions={}", table, updated, collisions);
        } catch (RuntimeException ex) {
            log.warn("normalization.backfill.failed table={} reason={}", table, ex.getClass().getSimpleName());
        }
        return updated;
    }

    protected void backfillProductNorms() {
        try {
            var products = productRepository.findNeedingNormBackfill();
            if (products.isEmpty()) return;
            for (var product : products) {
                product.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(product.getGenericName()));
                product.setBrandNorm(DescriptionNormalizer.normalizeOrNull(product.getBrand()));
            }
            productRepository.saveAll(products);
            log.info("normalization.backfill table=products norm_filled={}", products.size());
        } catch (RuntimeException ex) {
            log.warn("normalization.backfill.failed table=products reason={}", ex.getClass().getSimpleName());
        }
    }
}
