package com.relyon.economizaai.service;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductScan;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ScanMatchType;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ProductScanRepository;
import com.relyon.economizaai.service.extraction.EanCatalogService;
import com.relyon.economizaai.service.extraction.ProductTypeResolver;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.relyon.economizaai.service.priceindex.ScanHistoryService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductScanLogServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private EanCatalogService eanCatalogService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private ProductScanRepository productScanRepository;
    @Mock private ProductTypeResolver productTypeResolver;
    @Mock private ScanHistoryService scanHistoryService;
    @Mock private PlatformTransactionManager transactionManager;

    private ProductScanLogService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ProductScanLogService(productRepository, eanCatalogService,
                viewerLocationService, productScanRepository, productTypeResolver,
                scanHistoryService, transactionManager);
        var household = Household.builder().id(UUID.randomUUID()).build();
        user = User.builder().id(UUID.randomUUID()).household(household).build();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(viewerLocationService.resolve(any()))
                .thenReturn(new ViewerLocation(new BigDecimal("-30.03"), new BigDecimal("-51.22"),
                        ViewerLocationService.Source.RECEIPTS_MEDIAN, "RS"));
    }

    private ProductScan captureSaved() {
        var captor = ArgumentCaptor.forClass(ProductScan.class);
        verify(productScanRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void logsExactMatchWithProductAndGenericType() {
        var productId = UUID.randomUUID();
        var product = Product.builder().id(productId).normalizedName("Creme de Leite Nestlé")
                .genericName("Creme de Leite").genericNameNorm("creme de leite").build();
        when(productRepository.findByEan("7891000100103")).thenReturn(Optional.of(product));

        service.record(user, "7891000100103");

        var saved = captureSaved();
        assertThat(saved.getMatchType()).isEqualTo(ScanMatchType.EXACT);
        assertThat(saved.getMatchedProductId()).isEqualTo(productId);
        assertThat(saved.getGenericNameNorm()).isEqualTo("creme de leite");
        assertThat(saved.getUf()).isEqualTo("RS");
        assertThat(saved.getLocationSource()).isEqualTo("RECEIPTS_MEDIAN");
    }

    @Test
    void logsGenericWithCanonicalTypeFromNoisyCatalogName() {
        when(productRepository.findByEan("7891000100103")).thenReturn(Optional.empty());
        var entry = mock(EanCatalogEntry.class);
        when(eanCatalogService.lookup("7891000100103")).thenReturn(Optional.of(entry));
        // o resolver (gate → mapa → IA closed-set, com cache por EAN) transforma o nome cru/órfão
        // do catálogo no tipo canônico normalizado
        when(productTypeResolver.resolveCatalogTypeKey(entry)).thenReturn("creme de leite");

        service.record(user, "7891000100103");

        var saved = captureSaved();
        assertThat(saved.getMatchType()).isEqualTo(ScanMatchType.GENERIC);
        assertThat(saved.getMatchedProductId()).isNull();
        assertThat(saved.getGenericNameNorm()).isEqualTo("creme de leite");
    }

    @Test
    void logsNoneWhenUnknown() {
        when(productRepository.findByEan("7891000100103")).thenReturn(Optional.empty());
        when(eanCatalogService.lookup("7891000100103")).thenReturn(Optional.empty());

        service.record(user, "7891000100103");

        assertThat(captureSaved().getMatchType()).isEqualTo(ScanMatchType.NONE);
    }

    @Test
    void skipsTooShortEan() {
        service.record(user, "123");
        verify(productScanRepository, never()).save(any());
    }

    @Test
    void swallowsFailureSoLookupNeverBreaks() {
        when(productRepository.findByEan(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service.record(user, "7891000100103")).doesNotThrowAnyException();
    }
}
