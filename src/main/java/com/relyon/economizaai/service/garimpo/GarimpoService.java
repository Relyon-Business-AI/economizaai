package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.config.GarimpoProperties;
import com.relyon.economizaai.dto.request.GarimpoWatchRequest;
import com.relyon.economizaai.dto.response.GarimpoMarketplaceResponse;
import com.relyon.economizaai.dto.response.GarimpoProductResponse;
import com.relyon.economizaai.dto.response.GarimpoSearchResponse;
import com.relyon.economizaai.dto.response.GarimpoSnapshotResponse;
import com.relyon.economizaai.dto.response.GarimpoWatchResponse;
import com.relyon.economizaai.dto.response.GarimpoWatchRunResponse;
import com.relyon.economizaai.exception.EcommerceProviderException;
import com.relyon.economizaai.exception.GarimpoMarketplaceNotFoundException;
import com.relyon.economizaai.exception.GarimpoProviderNotConfiguredException;
import com.relyon.economizaai.exception.GarimpoSearchFailedException;
import com.relyon.economizaai.exception.GarimpoWatchNotFoundException;
import com.relyon.economizaai.exception.InvalidGarimpoWatchException;
import com.relyon.economizaai.model.GarimpoWatch;
import com.relyon.economizaai.repository.GarimpoWatchRepository;
import com.relyon.economizaai.service.ecommerce.EcommerceProvider;
import com.relyon.economizaai.service.ecommerce.ProviderSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The garimpo (deal-hunting) facade: live term searches that feed the price history,
 * the history itself, and the watches (standing searches with alerts). Admin-only
 * surface — see AdminGarimpoController.
 */
@Slf4j
@Service
public class GarimpoService {

    private static final String DEFAULT_PROVIDER = "mercadolivre";

    private final Map<String, EcommerceProvider> providersByKey;
    private final GarimpoWatchRepository watchRepository;
    private final GarimpoSnapshotRecorder snapshotRecorder;
    private final GarimpoWatchRunner watchRunner;
    private final GarimpoProperties properties;

    public GarimpoService(List<EcommerceProvider> providers,
                          GarimpoWatchRepository watchRepository,
                          GarimpoSnapshotRecorder snapshotRecorder,
                          GarimpoWatchRunner watchRunner,
                          GarimpoProperties properties) {
        this.providersByKey = providers.stream()
                .collect(Collectors.toMap(EcommerceProvider::key, Function.identity()));
        this.watchRepository = watchRepository;
        this.snapshotRecorder = snapshotRecorder;
        this.watchRunner = watchRunner;
        this.properties = properties;
    }

    public GarimpoSearchResponse search(String term, String providerKey, int offset, int limit,
                                        Integer minDiscountPercent) {
        var provider = requireConfiguredProvider(normalizedProviderKey(providerKey));
        var boundedOffset = Math.max(offset, 0);
        var boundedLimit = Math.min(Math.max(limit, 1), properties.getSearchMaxLimit());
        var result = searchProvider(provider, term, boundedOffset, boundedLimit);
        var products = minDiscountPercent == null
                ? result.products()
                : result.products().stream()
                        .filter(product -> product.discountPercent() != null
                                && product.discountPercent() >= minDiscountPercent)
                        .toList();
        var snapshotsWritten = snapshotRecorder.recordChangedPrices(result.products());
        log.info("garimpo.search done term={} provider={} total={} returned={} snapshotsWritten={}",
                term, provider.key(), result.total(), products.size(), snapshotsWritten);
        return new GarimpoSearchResponse(term, provider.key(), result.total(), boundedOffset, boundedLimit,
                products.stream().map(GarimpoProductResponse::from).toList());
    }

    public List<GarimpoMarketplaceResponse> marketplaces() {
        return providersByKey.values().stream()
                .map(provider -> new GarimpoMarketplaceResponse(provider.key(), provider.isConfigured()))
                .sorted(Comparator.comparing(GarimpoMarketplaceResponse::key))
                .toList();
    }

    public Page<GarimpoSnapshotResponse> history(String providerKey, String externalId, Pageable pageable) {
        var normalizedKey = normalizedProviderKey(providerKey);
        if (!providersByKey.containsKey(normalizedKey)) {
            throw new GarimpoMarketplaceNotFoundException(normalizedKey);
        }
        return snapshotRecorder.pageHistory(normalizedKey, externalId, pageable)
                .map(GarimpoSnapshotResponse::from);
    }

    public Page<GarimpoWatchResponse> listWatches(Pageable pageable) {
        return watchRepository.findAllByOrderByCreatedAtDesc(pageable).map(GarimpoWatchResponse::from);
    }

    public GarimpoWatchResponse createWatch(GarimpoWatchRequest request, String adminEmail) {
        validateCriteria(request);
        var providerKey = normalizedProviderKey(request.marketplace());
        requireKnownProvider(providerKey);
        var watch = GarimpoWatch.builder()
                .searchTerm(request.searchTerm().trim())
                .provider(providerKey)
                .targetPrice(request.targetPrice())
                .minDiscountPercent(request.minDiscountPercent())
                .active(request.active() == null || request.active())
                .createdBy(adminEmail)
                .build();
        var saved = watchRepository.save(watch);
        log.info("garimpo.watch.created watch={} term={} provider={} targetPrice={} minDiscount={}",
                saved.getId(), saved.getSearchTerm(), saved.getProvider(),
                saved.getTargetPrice(), saved.getMinDiscountPercent());
        return GarimpoWatchResponse.from(saved);
    }

    public GarimpoWatchResponse updateWatch(UUID watchId, GarimpoWatchRequest request) {
        validateCriteria(request);
        var providerKey = normalizedProviderKey(request.marketplace());
        requireKnownProvider(providerKey);
        var watch = requireWatch(watchId);
        watch.setSearchTerm(request.searchTerm().trim());
        watch.setProvider(providerKey);
        watch.setTargetPrice(request.targetPrice());
        watch.setMinDiscountPercent(request.minDiscountPercent());
        if (request.active() != null) {
            watch.setActive(request.active());
        }
        var saved = watchRepository.save(watch);
        log.info("garimpo.watch.updated watch={} term={} active={}",
                saved.getId(), saved.getSearchTerm(), saved.isActive());
        return GarimpoWatchResponse.from(saved);
    }

    public void deleteWatch(UUID watchId) {
        var watch = requireWatch(watchId);
        watchRepository.delete(watch);
        log.info("garimpo.watch.deleted watch={} term={}", watchId, watch.getSearchTerm());
    }

    public GarimpoWatchRunResponse runWatchNow(UUID watchId) {
        var watch = requireWatch(watchId);
        var outcome = watchRunner.run(watch);
        return new GarimpoWatchRunResponse(
                GarimpoWatchResponse.from(watch),
                outcome.hits().stream().map(GarimpoProductResponse::from).toList(),
                outcome.webhookNotified());
    }

    private ProviderSearchResult searchProvider(EcommerceProvider provider, String term,
                                                int offset, int limit) {
        try {
            return provider.searchByTerm(term, offset, limit);
        } catch (EcommerceProviderException exception) {
            log.warn("garimpo.search failed term={} provider={} error={}",
                    term, provider.key(), exception.getMessage());
            throw new GarimpoSearchFailedException(provider.key());
        }
    }

    private void validateCriteria(GarimpoWatchRequest request) {
        if (request.targetPrice() == null && request.minDiscountPercent() == null) {
            throw new InvalidGarimpoWatchException();
        }
    }

    private GarimpoWatch requireWatch(UUID watchId) {
        return watchRepository.findById(watchId)
                .orElseThrow(() -> new GarimpoWatchNotFoundException(watchId.toString()));
    }

    private EcommerceProvider requireConfiguredProvider(String providerKey) {
        var provider = requireKnownProvider(providerKey);
        if (!provider.isConfigured()) {
            throw new GarimpoProviderNotConfiguredException(providerKey);
        }
        return provider;
    }

    private EcommerceProvider requireKnownProvider(String providerKey) {
        var provider = providersByKey.get(providerKey);
        if (provider == null) {
            throw new GarimpoMarketplaceNotFoundException(providerKey);
        }
        return provider;
    }

    private static String normalizedProviderKey(String providerKey) {
        return providerKey == null || providerKey.isBlank() ? DEFAULT_PROVIDER : providerKey.trim().toLowerCase();
    }
}
