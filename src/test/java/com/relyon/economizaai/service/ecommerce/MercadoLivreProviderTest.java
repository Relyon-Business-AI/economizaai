package com.relyon.economizaai.service.ecommerce;

import com.relyon.economizaai.config.EcommerceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class MercadoLivreProviderTest {

    private MercadoLivreProvider provider(EcommerceProperties properties) {
        return new MercadoLivreProvider(properties, RestClient.builder());
    }

    @Test
    void inertWhenMasterDisabled() {
        var provider = provider(new EcommerceProperties());
        assertThat(provider.key()).isEqualTo("mercadolivre");
        assertThat(provider.isConfigured()).isFalse();
        // Must not touch the network when unconfigured.
        assertThat(provider.searchByEan("7891234567890", null)).isEmpty();
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
        var properties = new EcommerceProperties();
        properties.setEnabled(true);
        var block = new EcommerceProperties.Provider();
        block.setEnabled(true);
        block.setClientId("app-id");
        block.setClientSecret("secret");
        properties.getProviders().put("mercadolivre", block);

        assertThat(provider(properties).isConfigured()).isTrue();
    }
}
