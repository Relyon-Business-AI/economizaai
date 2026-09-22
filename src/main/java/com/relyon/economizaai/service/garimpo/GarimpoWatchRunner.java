package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.config.GarimpoProperties;
import com.relyon.economizaai.exception.EcommerceProviderException;
import com.relyon.economizaai.exception.GarimpoMarketplaceNotFoundException;
import com.relyon.economizaai.exception.GarimpoProviderNotConfiguredException;
import com.relyon.economizaai.exception.GarimpoSearchFailedException;
import com.relyon.economizaai.model.GarimpoPriceSnapshot;
import com.relyon.economizaai.model.GarimpoWatch;
import com.relyon.economizaai.repository.GarimpoWatchRepository;
import com.relyon.economizaai.service.ecommerce.EcommerceProvider;
import com.relyon.economizaai.service.ecommerce.ProviderProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sweeps active garimpo watches: re-runs each standing search, appends changed prices
 * to the history, and alerts the webhook on NEW hits only — a product already alerted
 * at the same (or lower) price doesn't re-fire, so the group isn't spammed with the
 * same deal every sweep; a further price drop fires again.
 *
 * <p>Each watch runs isolated (one failing search can't kill the sweep) and the
 * outbound HTTP (marketplace search, webhook) never runs inside a DB transaction.
 */
@Slf4j
@Service
public class GarimpoWatchRunner {

    public record RunOutcome(List<ProviderProduct> hits, boolean webhookNotified) {
    }

    private final Map<String, EcommerceProvider> providersByKey;
    private final GarimpoWatchRepository watchRepository;
    private final GarimpoSnapshotRecorder snapshotRecorder;
    private final GarimpoAlertWebhookClient webhookClient;
    private final GarimpoProperties properties;

    public GarimpoWatchRunner(List<EcommerceProvider> providers,
                              GarimpoWatchRepository watchRepository,
                              GarimpoSnapshotRecorder snapshotRecorder,
                              GarimpoAlertWebhookClient webhookClient,
                              GarimpoProperties properties) {
        this.providersByKey = providers.stream()
                .collect(Collectors.toMap(EcommerceProvider::key, Function.identity()));
        this.watchRepository = watchRepository;
        this.snapshotRecorder = snapshotRecorder;
        this.webhookClient = webhookClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${economizaai.garimpo.watch-sweep-delay-ms:3600000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        var activeWatches = watchRepository.findByActiveTrue();
        if (activeWatches.isEmpty()) {
            return;
        }
        var totalHits = 0;
        var failures = 0;
        for (var watch : activeWatches) {
            try {
                totalHits += run(watch).hits().size();
            } catch (RuntimeException exception) {
                failures++;
                log.warn("garimpo.watch.run_failed watch={} term={} reason={}",
                        abbrev(watch), watch.getSearchTerm(), exception.getMessage());
            }
        }
        log.info("garimpo.watch.swept watches={} hits={} failures={}",
                activeWatches.size(), totalHits, failures);
    }

    public RunOutcome run(GarimpoWatch watch) {
        var provider = requireConfiguredProvider(watch.getProvider());
        var result = searchWatchTerm(provider, watch);
        var newHits = result.stream()
                .filter(product -> qualifies(watch, product.price(), product.discountPercent()))
                .filter(product -> isNewHit(watch, product))
                .toList();
        snapshotRecorder.recordChangedPrices(result);
        watch.setLastRunAt(LocalDateTime.now());
        watchRepository.save(watch);
        var notified = !newHits.isEmpty() && webhookClient.notifyHits(watch, newHits);
        log.info("garimpo.watch.ran watch={} term={} results={} newHits={} notified={}",
                abbrev(watch), watch.getSearchTerm(), result.size(), newHits.size(), notified);
        return new RunOutcome(newHits, notified);
    }

    private List<ProviderProduct> searchWatchTerm(EcommerceProvider provider, GarimpoWatch watch) {
        try {
            return provider.searchByTerm(watch.getSearchTerm(), 0, properties.getSearchMaxLimit()).products();
        } catch (EcommerceProviderException exception) {
            log.warn("garimpo.watch.search_failed watch={} provider={} error={}",
                    abbrev(watch), provider.key(), exception.getMessage());
            throw new GarimpoSearchFailedException(provider.key());
        }
    }

    private EcommerceProvider requireConfiguredProvider(String providerKey) {
        var provider = providersByKey.get(providerKey);
        if (provider == null) {
            throw new GarimpoMarketplaceNotFoundException(providerKey);
        }
        if (!provider.isConfigured()) {
            throw new GarimpoProviderNotConfiguredException(providerKey);
        }
        return provider;
    }

    private boolean qualifies(GarimpoWatch watch, BigDecimal price, Integer discountPercent) {
        var hitsTargetPrice = watch.getTargetPrice() != null
                && price != null
                && price.compareTo(watch.getTargetPrice()) <= 0;
        var hitsMinDiscount = watch.getMinDiscountPercent() != null
                && discountPercent != null
                && discountPercent >= watch.getMinDiscountPercent();
        return hitsTargetPrice || hitsMinDiscount;
    }

    /**
     * A hit is NEW when the product has no prior snapshot, its latest snapshot didn't
     * qualify for this watch, or the price dropped further since it last qualified.
     */
    private boolean isNewHit(GarimpoWatch watch, ProviderProduct product) {
        if (product.externalId() == null) {
            return true;
        }
        return snapshotRecorder.latestSnapshot(product.providerKey(), product.externalId())
                .map(latest -> !alreadyAlertedAtOrBelow(watch, latest, product))
                .orElse(true);
    }

    private boolean alreadyAlertedAtOrBelow(GarimpoWatch watch, GarimpoPriceSnapshot latest,
                                            ProviderProduct product) {
        return qualifies(watch, latest.getPrice(), latest.getDiscountPercent())
                && latest.getPrice().compareTo(product.price()) <= 0;
    }

    private static String abbrev(GarimpoWatch watch) {
        return watch.getId() == null ? null : watch.getId().toString().substring(0, 8);
    }
}
