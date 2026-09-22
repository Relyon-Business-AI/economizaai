package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.config.GarimpoProperties;
import com.relyon.economizaai.exception.EcommerceProviderException;
import com.relyon.economizaai.exception.GarimpoProviderNotConfiguredException;
import com.relyon.economizaai.model.GarimpoPriceSnapshot;
import com.relyon.economizaai.model.GarimpoWatch;
import com.relyon.economizaai.repository.GarimpoWatchRepository;
import com.relyon.economizaai.service.ecommerce.EcommerceProvider;
import com.relyon.economizaai.service.ecommerce.ProviderProduct;
import com.relyon.economizaai.service.ecommerce.ProviderSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarimpoWatchRunnerTest {

    @Mock
    private EcommerceProvider mercadoLivre;

    @Mock
    private GarimpoWatchRepository watchRepository;

    @Mock
    private GarimpoSnapshotRecorder snapshotRecorder;

    @Mock
    private GarimpoAlertWebhookClient webhookClient;

    private GarimpoProperties properties;
    private GarimpoWatchRunner runner;

    @BeforeEach
    void setUp() {
        when(mercadoLivre.key()).thenReturn("mercadolivre");
        properties = new GarimpoProperties();
        runner = new GarimpoWatchRunner(List.of(mercadoLivre), watchRepository, snapshotRecorder,
                webhookClient, properties);
    }

    private GarimpoWatch watch(String targetPrice, Integer minDiscountPercent) {
        return GarimpoWatch.builder()
                .id(UUID.randomUUID())
                .searchTerm("air fryer")
                .provider("mercadolivre")
                .targetPrice(targetPrice == null ? null : new BigDecimal(targetPrice))
                .minDiscountPercent(minDiscountPercent)
                .build();
    }

    private ProviderProduct product(String externalId, String price, Integer discountPercent) {
        return new ProviderProduct("mercadolivre", externalId, "Produto " + externalId,
                new BigDecimal(price), null, discountPercent, "BRL",
                "https://ml/" + externalId, null, null, "LOJA", false);
    }

    private void providerReturns(ProviderProduct... products) {
        when(mercadoLivre.isConfigured()).thenReturn(true);
        when(mercadoLivre.searchByTerm(anyString(), anyInt(), anyInt()))
                .thenReturn(new ProviderSearchResult(products.length, List.of(products)));
    }

    @Test
    void targetPriceHitNotifiesWebhookAndStampsLastRun() {
        providerReturns(product("MLB111", "250.00", null), product("MLB222", "400.00", null));
        when(snapshotRecorder.latestSnapshot(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookClient.notifyHits(any(), anyList())).thenReturn(true);
        var watch = watch("300.00", null);

        var outcome = runner.run(watch);

        assertThat(outcome.hits()).hasSize(1);
        assertThat(outcome.hits().get(0).externalId()).isEqualTo("MLB111");
        assertThat(outcome.webhookNotified()).isTrue();
        assertThat(watch.getLastRunAt()).isNotNull();
        verify(watchRepository).save(watch);
        verify(snapshotRecorder).recordChangedPrices(anyList());
    }

    @Test
    void minDiscountHitQualifies() {
        providerReturns(product("MLB111", "99.90", 33), product("MLB222", "39.90", null));
        when(snapshotRecorder.latestSnapshot(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookClient.notifyHits(any(), anyList())).thenReturn(true);

        var outcome = runner.run(watch(null, 30));

        assertThat(outcome.hits()).extracting(ProviderProduct::externalId).containsExactly("MLB111");
    }

    @Test
    void alreadyAlertedDealDoesNotRefireAtSamePrice() {
        providerReturns(product("MLB111", "250.00", null));
        when(snapshotRecorder.latestSnapshot("mercadolivre", "MLB111"))
                .thenReturn(Optional.of(GarimpoPriceSnapshot.builder()
                        .price(new BigDecimal("250.00")).build()));

        var outcome = runner.run(watch("300.00", null));

        assertThat(outcome.hits()).isEmpty();
        verify(webhookClient, never()).notifyHits(any(), anyList());
    }

    @Test
    void furtherPriceDropRefires() {
        providerReturns(product("MLB111", "220.00", null));
        when(snapshotRecorder.latestSnapshot("mercadolivre", "MLB111"))
                .thenReturn(Optional.of(GarimpoPriceSnapshot.builder()
                        .price(new BigDecimal("250.00")).build()));
        when(webhookClient.notifyHits(any(), anyList())).thenReturn(true);

        var outcome = runner.run(watch("300.00", null));

        assertThat(outcome.hits()).hasSize(1);
        assertThat(outcome.webhookNotified()).isTrue();
    }

    @Test
    void runRejectsUnconfiguredProvider() {
        when(mercadoLivre.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> runner.run(watch("300.00", null)))
                .isInstanceOf(GarimpoProviderNotConfiguredException.class);
    }

    @Test
    void sweepIsolatesOneFailingWatch() {
        var failing = watch("300.00", null);
        var healthy = watch("300.00", null);
        when(watchRepository.findByActiveTrue()).thenReturn(List.of(failing, healthy));
        when(mercadoLivre.isConfigured()).thenReturn(true);
        when(mercadoLivre.searchByTerm(anyString(), anyInt(), anyInt()))
                .thenThrow(new EcommerceProviderException("portal down", null))
                .thenReturn(new ProviderSearchResult(1, List.of(product("MLB111", "250.00", null))));
        when(snapshotRecorder.latestSnapshot(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookClient.notifyHits(any(), anyList())).thenReturn(true);

        runner.sweep();

        // The second watch still ran end-to-end despite the first one's failure.
        verify(watchRepository).save(healthy);
        verify(webhookClient).notifyHits(any(), anyList());
    }

    @Test
    void sweepInertWhenDisabled() {
        properties.setEnabled(false);

        runner.sweep();

        verify(watchRepository, never()).findByActiveTrue();
    }
}
