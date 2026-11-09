package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductScan;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ScanMatchType;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ProductScanRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registra cada escaneamento de código de barras (fluxo admin "Comparar preço") na
 * tabela {@code product_scan}. Best-effort: classifica o EAN (exato/genérico/nenhum),
 * resolve a localização pela MESMA cadeia "perto de você" e grava numa transação
 * curta e independente (REQUIRES_NEW). NUNCA propaga falha — o log não pode quebrar
 * o lookup (é chamado de um {@code finally}).
 */
@Slf4j
@Service
public class ProductScanLogService {

    private final ProductRepository productRepository;
    private final EanCatalogService eanCatalogService;
    private final ViewerLocationService viewerLocationService;
    private final ProductScanRepository productScanRepository;
    private final TransactionTemplate scanTxTemplate;

    public ProductScanLogService(ProductRepository productRepository,
                                 EanCatalogService eanCatalogService,
                                 ViewerLocationService viewerLocationService,
                                 ProductScanRepository productScanRepository,
                                 PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.eanCatalogService = eanCatalogService;
        this.viewerLocationService = viewerLocationService;
        this.productScanRepository = productScanRepository;
        this.scanTxTemplate = new TransactionTemplate(transactionManager);
        this.scanTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Loga o scan. Swallows tudo — não pode escapar e quebrar o lookup. */
    public void record(User user, String rawEan) {
        try {
            var digits = rawEan == null ? "" : rawEan.replaceAll("\\D", "");
            if (digits.length() < 8) return;
            var scan = classify(user, digits);
            scanTxTemplate.executeWithoutResult(status -> productScanRepository.save(scan));
            log.info("scan.logged ean={} match={} generic={}", digits, scan.getMatchType(), scan.getGenericNameNorm());
        } catch (Exception logFailure) {
            log.warn("scan.log_failed ean={} reason={}", rawEan, logFailure.toString());
        }
    }

    private ProductScan classify(User user, String ean) {
        var scan = ProductScan.builder()
                .userId(user.getId())
                .householdId(user.getHousehold().getId())
                .ean(ean)
                .scannedAt(BrazilClock.nowOffset())
                .build();
        applyLocation(scan, viewerLocationService.resolve(user));

        var product = productRepository.findByEan(ean).orElse(null);
        if (product != null) {
            scan.setMatchType(ScanMatchType.EXACT);
            scan.setMatchedProductId(product.getId());
            scan.setGenericNameNorm(genericOf(product));
            return scan;
        }
        var catalogEntry = eanCatalogService.lookup(ean).orElse(null);
        if (catalogEntry != null) {
            scan.setMatchType(ScanMatchType.GENERIC);
            scan.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(catalogEntry.getGenericName()));
            return scan;
        }
        scan.setMatchType(ScanMatchType.NONE);
        return scan;
    }

    private static String genericOf(Product product) {
        return product.getGenericNameNorm() != null
                ? product.getGenericNameNorm()
                : DescriptionNormalizer.normalizeOrNull(product.getGenericName());
    }

    private static void applyLocation(ProductScan scan, ViewerLocation location) {
        scan.setLatitude(location.latitude());
        scan.setLongitude(location.longitude());
        scan.setUf(location.uf());
        scan.setLocationSource(location.source().name());
    }
}
