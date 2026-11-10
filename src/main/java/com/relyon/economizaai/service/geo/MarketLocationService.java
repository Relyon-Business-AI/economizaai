package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.service.geo.CnpjActivityClient.CnpjLookup;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.MarketChainRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages MarketLocation rows: registers a market entry the first time
 * we see its CNPJ on a confirmed receipt, then geocodes pending entries
 * on a periodic schedule (rate-limited per Nominatim policy).
 *
 * Geocoding is decoupled from the receipt-confirm path so we never
 * block the user on an external API call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLocationService {

    private static final int MAX_GEOCODE_ATTEMPTS = 3;
    private static final int MAX_SEGMENT_ATTEMPTS = 3;
    private static final int MAX_IBGE_BACKFILL_ATTEMPTS = 3;
    /**
     * segmentAttempts neste valor marca uma classificação MANUAL do admin: fica no limite das duas
     * filas do classificador CNAE ({@code SegmentAttemptsLessThan}), então o job nunca re-lookup
     * nem sobrescreve o veredito do admin. Use em {@code AdminMerchantService.classifyMerchant}.
     */
    public static final int MANUAL_SEGMENT_ATTEMPTS = MAX_SEGMENT_ATTEMPTS + MAX_IBGE_BACKFILL_ATTEMPTS;
    private static final String BRASIL_SUFFIX = ", Brasil";

    private final MarketLocationRepository repository;
    private final PriceObservationRepository priceObservationRepository;
    private final MarketChainRepository chainRepository;
    private final NominatimGeocoder geocoder;
    private final CnpjActivityClient cnpjActivityClient;
    private final CepLocationService cepLocationService;
    private final ProductRepository productRepository;
    private final CategoryResolver categoryResolver;
    private final ContactService contactService;

    // Self-reference so per-item @Transactional calls go through the Spring proxy
    // (a direct call would bypass it). Defaults to `this` for plain unit tests;
    // Spring replaces it with the lazy proxy at runtime.
    @Lazy
    @Autowired
    private MarketLocationService self = this;

    @Value("${economizaai.geo.geocode-delay-ms:1100}")
    private long geocodeDelayMs;

    /** Called from ReceiptService.confirm — idempotent register, never geocodes inline. */
    @Transactional
    public void registerMarketFromReceipt(Receipt receipt) {
        if (receipt.getCnpjEmitente() == null) return;
        register(receipt.getCnpjEmitente(), receipt.getMarketName(), receipt.getMarketAddress());
    }

    @Transactional
    public MarketLocation register(String cnpj, String name, String address) {
        var existing = repository.findByCnpj(cnpj);
        if (existing.isPresent()) return existing.get();
        var cnpjRoot = PriceIndexService.cnpjRoot(cnpj);
        var chain = chainRepository.findByCnpjRoot(cnpjRoot).orElse(null);
        var location = MarketLocation.builder()
                .cnpj(cnpj)
                .cnpjRoot(cnpjRoot)
                .name(name)
                .address(address)
                .chain(chain)
                .build();
        var saved = repository.save(location);
        log.info("market_location.registered cnpj={} name='{}'", cnpj, name);
        return saved;
    }

    /**
     * Ingest-time resolution for the merchant support gate: find-or-register the
     * market and, for a never-classified one, attempt the CNAE lookup right away
     * so a food-service merchant is rejected on its FIRST scan, not its second.
     * Called UNTRANSACTED from the ingestion pipeline (the lookup is an outbound
     * HTTP call); a lookup failure just leaves the segment UNKNOWN — the
     * scheduled classifier retries later and ingestion carries on.
     */
    public MarketLocation resolveForIngest(String cnpj, String name, String address) {
        if (cnpj == null || cnpj.isBlank()) return null;
        var market = self.register(cnpj, name, address);
        if (market.getSegment() == MerchantSegment.UNKNOWN
                && market.getSegmentAttempts() < MAX_SEGMENT_ATTEMPTS
                && cnpjActivityClient.isEnabled()) {
            self.classifySegmentOne(market);
        }
        return market;
    }

    @Scheduled(fixedDelayString = "${economizaai.geo.geocode-interval-ms:600000}",
               initialDelayString = "${economizaai.geo.geocode-initial-delay-ms:30000}")
    public void geocodePending() {
        var pending = repository.findAllByLatitudeIsNullAndGeocodeAttemptsLessThan(MAX_GEOCODE_ATTEMPTS);
        if (pending.isEmpty()) return;
        log.info("geocode.batch.start pending={}", pending.size());
        for (var market : pending) {
            if (!self.geocodeOne(market)) {
                log.warn("geocode.batch.paused reason=rate_limited remaining={}", pending.size() - pending.indexOf(market));
                return;
            }
            // Throttle to respect Nominatim's 1 req/sec policy
            try {
                Thread.sleep(geocodeDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("geocode.batch.done attempted={}", pending.size());
    }

    /**
     * Never holds a DB transaction across the Nominatim HTTP call — that would pin a
     * Hikari connection for the whole round-trip and, under load, starve the pool.
     * The query is built from the already-loaded entity, the geocode runs untransacted,
     * and only the result is persisted in a short transaction.
     */
    /** @return false when Nominatim rate-limited us: nothing is recorded, the market keeps its attempts. */
    public boolean geocodeOne(MarketLocation market) {
        var startedAt = Instant.now();
        var receiptAddress = MarketAddress.parse(market.getAddress());
        var result = geocodeFirstHit(receiptAddressQueries(market, receiptAddress));
        var knownAddress = receiptAddress.orElse(null);
        if (result.isEmpty() && !geocoder.rateLimitedSince(startedAt)) {
            var registryAddress = registryAddress(market);
            if (registryAddress != null) {
                result = geocodeFromRegistry(registryAddress);
                if (knownAddress == null) knownAddress = registryAddress;
            }
        }
        if (result.isEmpty() && MarketAddress.isMissing(market.getAddress()) && !geocoder.rateLimitedSince(startedAt)) {
            result = geocodeFirstHit(List.of(buildGeocodeQuery(market)));
        }
        if (result.isEmpty() && geocoder.rateLimitedSince(startedAt)) {
            log.warn("market_geocode.rate_limited cnpj={}", market.getCnpj());
            return false;
        }
        log.info("market_geocode.{} cnpj={} uf={}", result.isPresent() ? "ok" : "miss", market.getCnpj(),
                result.map(NominatimGeocoder.GeocodeResult::state).orElse(knownAddress != null ? knownAddress.uf() : null));
        self.persistGeocodeResult(market.getId(), result, knownAddress);
        return true;
    }

    /** Cleaned SEFAZ address when it parses; the raw text (minus empty segments) when it doesn't. */
    private List<String> receiptAddressQueries(MarketLocation market, Optional<MarketAddress> receiptAddress) {
        if (receiptAddress.isPresent()) return receiptAddress.get().geocodeQueries();
        if (MarketAddress.isMissing(market.getAddress())) return List.of();
        return List.of(withoutEmptySegments(market.getAddress()) + BRASIL_SUFFIX);
    }

    private MarketAddress registryAddress(MarketLocation market) {
        if (!cnpjActivityClient.isEnabled()) return null;
        return cnpjActivityClient.lookup(market.getCnpj()).registryAddress();
    }

    /** Receita address first; its CEP (BrasilAPI coordinates) when OSM doesn't know the street. */
    private Optional<NominatimGeocoder.GeocodeResult> geocodeFromRegistry(MarketAddress registryAddress) {
        var byStreet = geocodeFirstHit(registryAddress.geocodeQueries());
        if (byStreet.isPresent() || registryAddress.cep() == null) return byStreet;
        pauseForNominatim(); // the CEP lookup may geocode through Nominatim too
        return cepLocationService.resolve(registryAddress.cep())
                .map(cep -> new NominatimGeocoder.GeocodeResult(cep.latitude(), cep.longitude(),
                        registryAddress.city(), registryAddress.uf()));
    }

    /**
     * Tries each query in order, pausing before every call (Nominatim allows 1 req/sec, and a market may chain
     * receipt → registry queries). Stops at the first hit or at a 429 — more queries would only extend the ban.
     */
    private Optional<NominatimGeocoder.GeocodeResult> geocodeFirstHit(List<String> queries) {
        for (var query : queries) {
            pauseForNominatim();
            var calledAt = Instant.now();
            var result = geocoder.geocode(query);
            if (result.isPresent() || geocoder.rateLimitedSince(calledAt)) return result;
        }
        return Optional.empty();
    }

    private void pauseForNominatim() {
        try {
            Thread.sleep(geocodeDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String withoutEmptySegments(String address) {
        return Arrays.stream(address.split(","))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .collect(Collectors.joining(", "));
    }

    @Transactional
    public void persistGeocodeResult(UUID marketId, Optional<NominatimGeocoder.GeocodeResult> result) {
        persistGeocodeResult(marketId, result, null);
    }

    /**
     * On a miss, the city/UF we already know from the address still go in: they put the store in its state's
     * scope ("melhores preços no seu estado") even before it has coordinates.
     */
    @Transactional
    public void persistGeocodeResult(UUID marketId, Optional<NominatimGeocoder.GeocodeResult> result,
                                     MarketAddress knownAddress) {
        var market = repository.findById(marketId).orElse(null);
        if (market == null) return;
        market.setGeocodeAttempts(market.getGeocodeAttempts() + 1);
        if (result.isPresent()) {
            var hit = result.get();
            market.setLatitude(hit.latitude());
            market.setLongitude(hit.longitude());
            market.setCity(hit.city() != null ? hit.city() : cityOf(knownAddress));
            market.setState(hit.state() != null ? hit.state() : ufOf(knownAddress));
            market.setGeocodedAt(LocalDateTime.now());
            market.setGeocodeFailedAt(null);
            repository.save(market);
            propagateLocationToObservations(market);
        } else {
            if (market.getState() == null && knownAddress != null) {
                market.setCity(knownAddress.city());
                market.setState(knownAddress.uf());
            }
            market.setGeocodeFailedAt(LocalDateTime.now());
            repository.save(market);
        }
    }

    /**
     * After a market geocodes, fill city/state on its observations that were
     * written before (city/state are snapshotted at write time and otherwise
     * never back-filled). Only NULL-city rows are touched — existing snapshots
     * are preserved.
     */
    private void propagateLocationToObservations(MarketLocation market) {
        if (market.getCity() == null && market.getState() == null) return;
        var updated = priceObservationRepository.backfillLocationForMarket(
                market.getCnpj(), market.getCity(), market.getState());
        if (updated > 0) {
            log.info("geocode.observation_backfill cnpj={} city={} state={} observations={}",
                    market.getCnpj(), market.getCity(), market.getState(), updated);
        }
    }

    private static String cityOf(MarketAddress address) {
        return address != null ? address.city() : null;
    }

    private static String ufOf(MarketAddress address) {
        return address != null ? address.uf() : null;
    }

    /**
     * Verifies each unclassified market's business segment from its CNPJ's CNAE.
     * Decoupled from the confirm flow (like geocoding) and best-effort: a lookup
     * failure just leaves the segment UNKNOWN (attempts capped), and ingestion /
     * categorization carry on normally without business-type context. When a
     * market resolves to PHARMACY, OTHER products bought there are backfilled.
     */
    @Scheduled(fixedDelayString = "${economizaai.merchant.classify.interval-ms:3600000}",
               initialDelayString = "${economizaai.merchant.classify.initial-delay-ms:45000}")
    public void scheduledSegmentClassification() {
        classifyPendingSegments();
    }

    /**
     * Classifies every still-UNKNOWN market (attempts capped). Also the
     * admin-triggered backfill entry point. Returns a per-segment summary.
     */
    public SegmentClassificationSummary classifyPendingSegments() {
        if (!cnpjActivityClient.isEnabled()) return new SegmentClassificationSummary(0, 0, 0, 0, 0, 0, 0);
        var pending = new ArrayList<>(repository.findAllBySegmentAndSegmentAttemptsLessThan(
                MerchantSegment.UNKNOWN, MAX_SEGMENT_ATTEMPTS));
        // Backfill: rows classified before we captured the IBGE code. Bounded by
        // the attempts counter (incremented per lookup) so a registry that never
        // returns the code can't be polled forever.
        pending.addAll(repository.findAllByIbgeCityCodeIsNullAndSegmentNotAndSegmentAttemptsLessThan(
                MerchantSegment.UNKNOWN, MAX_SEGMENT_ATTEMPTS + MAX_IBGE_BACKFILL_ATTEMPTS));
        if (pending.isEmpty()) return new SegmentClassificationSummary(0, 0, 0, 0, 0, 0, 0);
        log.info("merchant.classify.batch.start pending={}", pending.size());
        var pharmacy = 0;
        var supermarket = 0;
        var foodRetail = 0;
        var foodService = 0;
        var other = 0;
        var unknown = 0;
        for (var market : pending) {
            self.classifySegmentOne(market);
            switch (market.getSegment()) {
                case PHARMACY -> pharmacy++;
                case SUPERMARKET -> supermarket++;
                case FOOD_RETAIL -> foodRetail++;
                case FOOD_SERVICE -> foodService++;
                case OTHER -> other++;
                case UNKNOWN -> unknown++;
            }
        }
        log.info("merchant.classify.batch.done attempted={} pharmacy={} supermarket={} foodRetail={} foodService={} other={} stillUnknown={}",
                pending.size(), pharmacy, supermarket, foodRetail, foodService, other, unknown);
        return new SegmentClassificationSummary(pending.size(), pharmacy, supermarket, foodRetail, foodService,
                other, unknown);
    }

    public record SegmentClassificationSummary(int attempted, int pharmacy, int supermarket, int foodRetail,
                                               int foodService, int other, int stillUnknown) {}

    /**
     * Classify one market from its CNPJ's CNAE. The lookup is an outbound HTTP call, so it runs
     * UNTRANSACTED — never pins a Hikari connection across the round-trip (same split as
     * {@link #geocodeOne}/{@link #persistGeocodeResult}). Only the result is persisted, in a short
     * transaction; the grey-sighting admin alert (also network) and the pharmacy backfill (its own
     * short tx) run afterwards, outside any long-held connection.
     */
    public void classifySegmentOne(MarketLocation market) {
        var lookup = cnpjActivityClient.lookup(market.getCnpj());
        var newGraySighting = self.persistSegmentResult(market, lookup);
        if (newGraySighting) {
            notifyGraySighting(market);
        }
        if (lookup.segment() == MerchantSegment.PHARMACY) {
            self.backfillPharmacyProducts(market.getCnpj());
        }
    }

    /**
     * Persist a CNAE lookup result in a short transaction. Increments the bounded attempts counter,
     * sets segment/IBGE/CNAE when resolved, and returns true only the FIRST time the market lands in
     * the grey zone (segment OTHER) — the caller then sends the one-per-CNPJ admin alert outside the tx.
     */
    @Transactional
    public boolean persistSegmentResult(MarketLocation market, CnpjLookup lookup) {
        market.setSegmentAttempts(market.getSegmentAttempts() + 1);
        var segment = lookup.segment();
        if (segment != MerchantSegment.UNKNOWN) {
            market.setSegment(segment);
            market.setSegmentClassifiedAt(LocalDateTime.now());
            market.setCnaeCodes(String.join(",", lookup.cnaeCodes()));
            log.info("merchant.classify.ok cnpj={} segment={} ibgeCityCode={} name='{}'",
                    market.getCnpj(), segment, lookup.ibgeCityCode(), market.getName());
        }
        if (market.getIbgeCityCode() == null && lookup.ibgeCityCode() != null) {
            market.setIbgeCityCode(lookup.ibgeCityCode());
        }
        var newGraySighting = segment == MerchantSegment.OTHER && market.getGraySightingNotifiedAt() == null;
        if (newGraySighting) {
            market.setGraySightingNotifiedAt(LocalDateTime.now());
        }
        repository.save(market);
        return newGraySighting;
    }

    /**
     * A merchant classified outside every known segment (grey zone — maybe an
     * informal padaria worth supporting, maybe not) needs a human decision: its
     * receipts are ingested for the user but held out of the collaborative index
     * until the admin promotes or blocks it. One alert per CNPJ, ever.
     */
    private void notifyGraySighting(MarketLocation market) {
        log.warn("merchant.gray_sighting cnpj={} name='{}' cnaes={}",
                market.getCnpj(), market.getName(), market.getCnaeCodes());
        try {
            contactService.notifyAdmin(
                    "novo estabelecimento fora dos segmentos suportados: " + market.getName(),
                    """
                    Um usuário escaneou uma nota de um estabelecimento que não é mercado, \
                    farmácia nem varejo de alimentos. A nota FOI processada para o usuário, \
                    mas os preços estão FORA do índice colaborativo até você decidir.

                    - CNPJ: %s
                    - Nome: %s
                    - CNAEs: %s
                    - Cidade/UF: %s/%s

                    Para decidir: GET /api/v1/admin/merchants/grey lista os pendentes; \
                    PUT /api/v1/admin/merchants/{cnpj}/support com {"override":"SUPPORTED"} \
                    promove (e retroalimenta o índice) ou {"override":"BLOCKED"} passa a \
                    rejeitar novas notas.
                    """.formatted(market.getCnpj(), market.getName(), market.getCnaeCodes(),
                            market.getCity(), market.getState()));
        } catch (RuntimeException ex) {
            log.warn("merchant.gray_sighting.notify_failed cnpj={} reason={}",
                    market.getCnpj(), ex.getClass().getSimpleName());
        }
    }

    /** Re-tag OTHER products bought at a now-verified pharmacy as PHARMACY. Own short tx. */
    @Transactional
    public void backfillPharmacyProducts(String cnpj) {
        var products = productRepository.findOtherCategoryProductsByMerchant(cnpj);
        for (var product : products) {
            product.setCategory(ProductCategory.HEALTH);
            // Escritor de ENUM: move o nó junto (listener é node-first).
            product.setCategoryId(categoryResolver.reconcileNode(ProductCategory.HEALTH, product.getCategoryId()));
            product.setCategorizationSource(CategorizationSource.MERCHANT);
        }
        if (!products.isEmpty()) {
            productRepository.saveAll(products);
            log.info("merchant.classify.backfill cnpj={} products={}", cnpj, products.size());
        }
    }

    /** Bulk lookup helper for queries that need lat/lng for many CNPJs. */
    @Transactional(readOnly = true)
    public Map<String, MarketLocation> findByCnpjs(List<String> cnpjs) {
        if (cnpjs == null || cnpjs.isEmpty()) return Map.of();
        return repository.findAllByCnpjIn(cnpjs).stream()
                .collect(Collectors.toMap(MarketLocation::getCnpj, m -> m));
    }

    private String buildGeocodeQuery(MarketLocation market) {
        if (market.getAddress() != null && !market.getAddress().isBlank()) {
            return market.getAddress() + BRASIL_SUFFIX;
        }
        if (market.getName() != null && !market.getName().isBlank()) {
            return market.getName() + BRASIL_SUFFIX;
        }
        return "CNPJ " + market.getCnpj() + BRASIL_SUFFIX;
    }
}
