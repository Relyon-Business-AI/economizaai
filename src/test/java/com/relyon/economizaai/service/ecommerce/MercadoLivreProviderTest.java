package com.relyon.economizaai.service.ecommerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.EcommerceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MercadoLivreProviderTest {

    private MercadoLivreProvider provider(EcommerceProperties properties) {
        return new MercadoLivreProvider(properties, RestClient.builder());
    }

    private EcommerceProperties configuredProperties() {
        var properties = new EcommerceProperties();
        properties.setEnabled(true);
        var block = new EcommerceProperties.Provider();
        block.setEnabled(true);
        block.setClientId("app-id");
        block.setClientSecret("secret");
        block.setAffiliateTag("economizaai");
        properties.getProviders().put("mercadolivre", block);
        return properties;
    }

    @Test
    void inertWhenMasterDisabled() {
        var provider = provider(new EcommerceProperties());
        assertThat(provider.key()).isEqualTo("mercadolivre");
        assertThat(provider.isConfigured()).isFalse();
        // Must not touch the network when unconfigured.
        assertThat(provider.searchByEan("7891234567890", null)).isEmpty();
        assertThat(provider.searchByTerm("air fryer", 0, 20).products()).isEmpty();
    }

    @Test
    void notConfiguredWhenEnabledButCredentialsBlank() {
        var properties = new EcommerceProperties();
        properties.setEnabled(true);
        var block = new EcommerceProperties.Provider();
        block.setEnabled(true); // but clientId/secret blank
        properties.getProviders().put("mercadolivre", block);

        assertThat(provider(properties).isConfigured()).isFalse();
    }

    @Test
    void configuredWhenEnabledWithCredentials() {
        assertThat(provider(configuredProperties()).isConfigured()).isTrue();
    }

    @Test
    void mapsTermSearchResultsFromRealPayload() throws IOException {
        var properties = configuredProperties();
        var body = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/fixtures/mercadolivre/search-fone-bluetooth.json"));

        var result = provider(properties).mapProducts(body, properties.provider("mercadolivre").orElseThrow());

        assertThat(result.total()).isEqualTo(1234);
        assertThat(result.products()).hasSize(2);

        var discounted = result.products().get(0);
        assertThat(discounted.externalId()).isEqualTo("MLB111");
        assertThat(discounted.price()).isEqualByComparingTo(new BigDecimal("99.90"));
        assertThat(discounted.originalPrice()).isEqualByComparingTo(new BigDecimal("149.90"));
        assertThat(discounted.discountPercent()).isEqualTo(33);
        assertThat(discounted.freeShipping()).isTrue();
        assertThat(discounted.sellerName()).isEqualTo("LOJA X");
        assertThat(discounted.affiliateUrl()).isEqualTo(
                "https://produto.mercadolivre.com.br/MLB111?matt_tool=economizaai");

        var fullPrice = result.products().get(1);
        assertThat(fullPrice.externalId()).isEqualTo("MLB222");
        assertThat(fullPrice.discountPercent()).isNull();
        assertThat(fullPrice.freeShipping()).isFalse();
        // Permalink already has a query string → the tag joins with '&'.
        assertThat(fullPrice.affiliateUrl()).isEqualTo(
                "https://produto.mercadolivre.com.br/MLB222?pdp_filters=cat&matt_tool=economizaai");
    }

    @Test
    void affiliateLinkFallsBackToPermalinkWithoutTag() throws IOException {
        var properties = configuredProperties();
        properties.provider("mercadolivre").orElseThrow().setAffiliateTag("");
        var body = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/fixtures/mercadolivre/search-fone-bluetooth.json"));

        var result = provider(properties).mapProducts(body, properties.provider("mercadolivre").orElseThrow());

        assertThat(result.products().get(0).affiliateUrl())
                .isEqualTo("https://produto.mercadolivre.com.br/MLB111");
    }
}
