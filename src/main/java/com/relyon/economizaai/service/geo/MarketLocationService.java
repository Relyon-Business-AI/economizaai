package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.ProductRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private static final String BRASIL_SUFFIX = ", Brasil";

    private final MarketLocationRepository repository;
    private final NominatimGeocoder geocoder;
    private final CnpjActivityClient cnpjActivityClient;
    private final ProductRepository productRepository;
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
        var location = MarketLocation.builder()
                .cnpj(cnpj)
                .cnpjRoot(PriceIndexService.cnpjRoot(cnpj))
                .name(name)
                .address(address)
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
            self.geocodeOne(market);
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
    public void geocodeOne(MarketLocation market) {
        var query = buildGeocodeQuery(market);
        var result = geocoder.geocode(query);
        self.persistGeocodeResult(market.getId(), result);
    }

    @Transactional
    public void persistGeocodeResult(UUID marketId, Optional<NominatimGeocoder.GeocodeResult> result) {
        var market = repository.findById(marketId).orElse(null);
        if (market == null) return;
        market.setGeocodeAttempts(market.getGeocodeAttempts() + 1);
        if (result.isPresent()) {
            var hit = result.get();
            market.setLatitude(hit.latitude());
            market.setLongitude(hit.longitude());
            market.setCity(hit.city());
            market.setState(hit.state());
            market.setGeocodedAt(LocalDateTime.now());
            market.setGeocodeFailedAt(null);
        } else {
            market.setGeocodeFailedAt(LocalDateTime.now());
        }
        repository.save(market);
    }

    /**
     * Verifies each unclassified market's business segment from its CNPJ's CNAE.
     * Decoupled from the confirm flow (like geocoding) and best-effort: a lookup
     * failure just leaves the segment UNKNOWN (attempts capped), and ingestion /
     * categorization carry on normally without business-type context. When a
     * market resolves to PHARMACY, OTHER products bought there are backfilled.
     */
    @Scheduled(fixedDelayString = "${economizaai.merchant.classify.interval-ms:600000}",
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

    @Transactional
    public void classifySegmentOne(MarketLocation market) {
        market.setSegmentAttempts(market.getSegmentAttempts() + 1);
        var lookup = cnpjActivityClient.lookup(market.getCnpj());
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
        if (segment == MerchantSegment.OTHER && market.getGraySightingNotifiedAt() == null) {
            market.setGraySightingNotifiedAt(LocalDateTime.now());
            notifyGraySighting(market);
        }
        repository.save(market);
        if (segment == MerchantSegment.PHARMACY) {
            backfillPharmacyProducts(market.getCnpj());
        }
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

    /** Re-tag OTHER products bought at a now-verified pharmacy as PHARMACY. */
    private void backfillPharmacyProducts(String cnpj) {
        var products = productRepository.findOtherCategoryProductsByMerchant(cnpj);
        for (var product : products) {
            product.setCategory(ProductCategory.HEALTH);
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
