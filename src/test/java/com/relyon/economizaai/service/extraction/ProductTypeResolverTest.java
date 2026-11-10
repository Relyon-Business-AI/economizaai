package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductTypeResolverTest {

    @Mock private ProductExtractor productExtractor;
    @Mock private ProductRepository productRepository;
    @Mock private EanCatalogRepository eanCatalogRepository;
    @Mock private AiGateway aiGateway;
    @Mock private PlatformTransactionManager transactionManager;

    private ProductTypeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProductTypeResolver(productExtractor, productRepository, eanCatalogRepository,
                aiGateway, transactionManager);
    }

    private ProductExtraction withGeneric(String genericName) {
        return new ProductExtraction(genericName, null, null, null, null, null, null);
    }

    private EanCatalogEntry catalogEntry(String ean, String name) {
        return EanCatalogEntry.builder().ean(ean).genericName(name).build();
    }

    /**
     * TransactionTemplate.execute drives the callback itself after getTransaction returns a status;
     * a mock status (commit/rollback are no-op mocks) lets the real callback run against the mocked
     * repo without a live tx manager.
     */
    private void stubTxManager() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    // ===== caminho existente (inalterado) =====

    @Test
    void typeKeyIsTheNormalizedCanonicalGenericNameFromTheExtractor() {
        when(productExtractor.extract("CREME DE LEITE UHT NESTLE")).thenReturn(withGeneric("Creme de Leite"));

        assertThat(resolver.canonicalGenericName("CREME DE LEITE UHT NESTLE")).isEqualTo("Creme de Leite");
        assertThat(resolver.typeKey("CREME DE LEITE UHT NESTLE")).isEqualTo("creme de leite");
    }

    @Test
    void fallsBackToRawNameWhenExtractorResolvesNoType() {
        when(productExtractor.extract("Xyz Marca Nova")).thenReturn(ProductExtraction.EMPTY);

        assertThat(resolver.canonicalGenericName("Xyz Marca Nova")).isEqualTo("Xyz Marca Nova");
        assertThat(resolver.typeKey("Xyz Marca Nova")).isEqualTo("xyz marca nova");
    }

    @Test
    void nullOrBlankYieldsNull() {
        assertThat(resolver.typeKey(null)).isNull();
        assertThat(resolver.typeKey("   ")).isNull();
        assertThat(resolver.canonicalGenericName(null)).isNull();
    }

    // ===== resolveCatalogTypeKey: cache → gate → mapa → IA closed-set =====

    /** (a) nome que já casa com o catálogo → sem mapa, sem IA. */
    @Test
    void alreadyMatchingNameResolvesWithoutMapOrAi() {
        var entry = catalogEntry("789", "Creme de Leite Nestlé");
        when(productExtractor.extract("Creme de Leite Nestlé")).thenReturn(withGeneric("Creme de Leite"));
        when(productRepository.existsByGenericNameNorm("creme de leite")).thenReturn(true);

        assertThat(resolver.resolveCatalogTypeKey(entry)).isEqualTo("creme de leite");

        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
        verify(eanCatalogRepository, never()).save(any());
    }

    /** (b) acerto no mapa estático ("White Rice" → "arroz") → resolve, sem IA. */
    @Test
    void staticMapHitResolvesWithoutAi() {
        var entry = catalogEntry("7896079431110", "White Rice");
        // O OFF guarda em inglês; o extrator não resolve → norm = "white rice".
        when(productExtractor.extract("White Rice")).thenReturn(ProductExtraction.EMPTY);
        // "white rice" não existe no catálogo, mas o alvo mapeado "arroz" existe.
        when(productRepository.existsByGenericNameNorm("white rice")).thenReturn(false);
        when(productRepository.existsByGenericNameNorm("arroz")).thenReturn(true);
        stubTxManager();
        when(eanCatalogRepository.findByEan("7896079431110")).thenReturn(Optional.of(entry));

        assertThat(resolver.resolveCatalogTypeKey(entry)).isEqualTo("arroz");

        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
        verify(eanCatalogRepository).save(entry);
        assertThat(entry.getResolvedGenericNameNorm()).isEqualTo("arroz");
    }

    /** Mapa só aceita o alvo se ELE existir no catálogo — alvo ausente volta pra IA. */
    @Test
    void staticMapTargetMustExistInCatalog() {
        var entry = catalogEntry("790", "Salt");
        when(productExtractor.extract("Salt")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.existsByGenericNameNorm("salt")).thenReturn(false);
        when(productRepository.existsByGenericNameNorm("sal")).thenReturn(false); // alvo mapeado não existe
        when(aiGateway.isEnabled()).thenReturn(false); // IA inerte → unresolved

        assertThat(resolver.resolveCatalogTypeKey(entry)).isNull();
        verify(eanCatalogRepository, never()).save(any());
    }

    /** (c) IA devolve um tipo válido do conjunto → resolve + grava cache. */
    @Test
    void aiReturningInSetTypeResolvesAndCaches() {
        var entry = catalogEntry("788", "Cereal Bar Flavour X");
        when(productExtractor.extract("Cereal Bar Flavour X")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.existsByGenericNameNorm(anyString())).thenReturn(false);
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(productRepository.findDistinctGenericNameNormsWithObservations())
                .thenReturn(List.of("arroz", "barra de cereal", "cafe"));
        when(aiGateway.complete(eq(AiActivity.TYPE_RESOLUTION), eq("claude-haiku-4-5"),
                anyString(), anyString(), anyInt())).thenReturn("barra de cereal");
        stubTxManager();
        when(eanCatalogRepository.findByEan("788")).thenReturn(Optional.of(entry));

        assertThat(resolver.resolveCatalogTypeKey(entry)).isEqualTo("barra de cereal");

        verify(eanCatalogRepository).save(entry);
        assertThat(entry.getResolvedGenericNameNorm()).isEqualTo("barra de cereal");
    }

    /** (d) IA devolve NONE → unresolved (sem mapeamento bogus, sem cache). */
    @Test
    void aiReturningNoneLeavesUnresolved() {
        var entry = catalogEntry("787", "Exotic Fruit Nobody Tracks");
        when(productExtractor.extract("Exotic Fruit Nobody Tracks")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.existsByGenericNameNorm(anyString())).thenReturn(false);
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(productRepository.findDistinctGenericNameNormsWithObservations())
                .thenReturn(List.of("arroz", "cafe"));
        when(aiGateway.complete(eq(AiActivity.TYPE_RESOLUTION), anyString(),
                anyString(), anyString(), anyInt())).thenReturn("NONE");

        assertThat(resolver.resolveCatalogTypeKey(entry)).isNull();
        verify(eanCatalogRepository, never()).save(any());
    }

    /** (d') IA devolve um tipo FORA do conjunto (alucinação) → unresolved. */
    @Test
    void aiReturningOutOfSetTypeLeavesUnresolved() {
        var entry = catalogEntry("786", "Mystery Item");
        when(productExtractor.extract("Mystery Item")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.existsByGenericNameNorm(anyString())).thenReturn(false);
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.extractorModel()).thenReturn("claude-haiku-4-5");
        when(productRepository.findDistinctGenericNameNormsWithObservations())
                .thenReturn(List.of("arroz", "cafe"));
        // Tipo inventado que não está na lista.
        when(aiGateway.complete(eq(AiActivity.TYPE_RESOLUTION), anyString(),
                anyString(), anyString(), anyInt())).thenReturn("macarrao instantaneo");

        assertThat(resolver.resolveCatalogTypeKey(entry)).isNull();
        verify(eanCatalogRepository, never()).save(any());
    }

    /** (e) valor em cache dá curto-circuito — sem extrator, sem IA. */
    @Test
    void cachedValueShortCircuits() {
        var entry = EanCatalogEntry.builder().ean("785").genericName("White Rice")
                .resolvedGenericNameNorm("arroz").build();

        assertThat(resolver.resolveCatalogTypeKey(entry)).isEqualTo("arroz");

        verify(productExtractor, never()).extract(anyString());
        verify(aiGateway, never()).isEnabled();
        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
    }

    /** IA inerte (sem API key) → sem chamada, estado vazio honesto (comportamento de hoje). */
    @Test
    void aiDisabledLeavesUnresolvedWithoutCall() {
        var entry = catalogEntry("784", "Some Foreign Name");
        when(productExtractor.extract("Some Foreign Name")).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.existsByGenericNameNorm(anyString())).thenReturn(false);
        when(aiGateway.isEnabled()).thenReturn(false);

        assertThat(resolver.resolveCatalogTypeKey(entry)).isNull();
        verify(aiGateway, never()).complete(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void nullEntryYieldsNull() {
        assertThat(resolver.resolveCatalogTypeKey(null)).isNull();
    }
}
