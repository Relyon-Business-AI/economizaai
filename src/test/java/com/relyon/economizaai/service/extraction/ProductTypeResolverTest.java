package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductTypeResolverTest {

    @Mock private ProductExtractor productExtractor;

    @InjectMocks private ProductTypeResolver resolver;

    private ProductExtraction withGeneric(String genericName) {
        return new ProductExtraction(genericName, null, null, null, null, null, null);
    }

    @Test
    void typeKeyIsTheNormalizedCanonicalGenericNameFromTheExtractor() {
        // O MESMO extrator da ingestão resolve o nome cru do catálogo no tipo canônico.
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
}
