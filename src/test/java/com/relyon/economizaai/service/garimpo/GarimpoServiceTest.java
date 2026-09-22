package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.config.GarimpoProperties;
import com.relyon.economizaai.dto.request.GarimpoWatchRequest;
import com.relyon.economizaai.exception.EcommerceProviderException;
import com.relyon.economizaai.exception.GarimpoMarketplaceNotFoundException;
import com.relyon.economizaai.exception.GarimpoProviderNotConfiguredException;
import com.relyon.economizaai.exception.GarimpoSearchFailedException;
import com.relyon.economizaai.exception.GarimpoWatchNotFoundException;
import com.relyon.economizaai.exception.InvalidGarimpoWatchException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarimpoServiceTest {

    @Mock
    private EcommerceProvider mercadoLivre;

    @Mock
    private GarimpoWatchRepository watchRepository;

    @Mock
    private GarimpoSnapshotRecorder snapshotRecorder;

    @Mock
    private GarimpoWatchRunner watchRunner;

    private GarimpoService service;

    @BeforeEach
    void setUp() {
        when(mercadoLivre.key()).thenReturn("mercadolivre");
        service = new GarimpoService(List.of(mercadoLivre), watchRepository, snapshotRecorder,
                watchRunner, new GarimpoProperties());
    }

    private ProviderProduct product(String externalId, String price, Integer discountPercent) {
        return new ProviderProduct("mercadolivre", externalId, "Produto " + externalId,
                new BigDecimal(price), null, discountPercent, "BRL",
                "https://ml/" + externalId, null, null, "LOJA", false);
    }

    @Test
    void searchRejectsUnknownMarketplace() {
        assertThatThrownBy(() -> service.search("air fryer", "shopee", 0, 20, null))
                .isInstanceOf(GarimpoMarketplaceNotFoundException.class);
    }

    @Test
    void searchRejectsUnconfiguredProvider() {
        when(mercadoLivre.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.search("air fryer", null, 0, 20, null))
                .isInstanceOf(GarimpoProviderNotConfiguredException.class);
    }

    @Test
    void searchFiltersResponseByMinDiscountButSnapshotsEverything() {
        when(mercadoLivre.isConfigured()).thenReturn(true);
        var discounted = product("MLB111", "99.90", 33);
        var fullPrice = product("MLB222", "39.90", null);
        when(mercadoLivre.searchByTerm(anyString(), anyInt(), anyInt()))
                .thenReturn(new ProviderSearchResult(2, List.of(discounted, fullPrice)));

        var response = service.search("fone bluetooth", null, 0, 20, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).externalId()).isEqualTo("MLB111");
        assertThat(response.total()).isEqualTo(2);
        // History keeps EVERY observed price, not just the ones shown.
        verify(snapshotRecorder).recordChangedPrices(List.of(discounted, fullPrice));
    }

    @Test
    void searchTranslatesProviderFailureIntoLocalizedError() {
        when(mercadoLivre.isConfigured()).thenReturn(true);
        when(mercadoLivre.searchByTerm(anyString(), anyInt(), anyInt()))
                .thenThrow(new EcommerceProviderException("boom", null));

        assertThatThrownBy(() -> service.search("air fryer", null, 0, 20, null))
                .isInstanceOf(GarimpoSearchFailedException.class);
    }

    @Test
    void createWatchRequiresAtLeastOneCriterion() {
        var request = new GarimpoWatchRequest("air fryer", null, null, null, null);

        assertThatThrownBy(() -> service.createWatch(request, "admin@economizaai.app"))
                .isInstanceOf(InvalidGarimpoWatchException.class);
    }

    @Test
    void createWatchDefaultsProviderAndActive() {
        when(watchRepository.save(any(GarimpoWatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new GarimpoWatchRequest("air fryer", null, new BigDecimal("300.00"), null, null);

        var response = service.createWatch(request, "admin@economizaai.app");

        assertThat(response.marketplace()).isEqualTo("mercadolivre");
        assertThat(response.active()).isTrue();
        assertThat(response.targetPrice()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(response.createdBy()).isEqualTo("admin@economizaai.app");
    }

    @Test
    void createWatchRejectsUnknownMarketplace() {
        var request = new GarimpoWatchRequest("air fryer", "shopee", new BigDecimal("300.00"), null, null);

        assertThatThrownBy(() -> service.createWatch(request, "admin@economizaai.app"))
                .isInstanceOf(GarimpoMarketplaceNotFoundException.class);
    }

    @Test
    void deleteWatchRejectsUnknownId() {
        var watchId = UUID.randomUUID();
        when(watchRepository.findById(watchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteWatch(watchId))
                .isInstanceOf(GarimpoWatchNotFoundException.class);
    }

    @Test
    void runWatchNowMapsRunnerOutcome() {
        var watchId = UUID.randomUUID();
        var watch = GarimpoWatch.builder()
                .searchTerm("air fryer").provider("mercadolivre")
                .targetPrice(new BigDecimal("300.00")).build();
        when(watchRepository.findById(watchId)).thenReturn(Optional.of(watch));
        when(watchRunner.run(watch)).thenReturn(
                new GarimpoWatchRunner.RunOutcome(List.of(product("MLB111", "250.00", null)), true));

        var response = service.runWatchNow(watchId);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.webhookNotified()).isTrue();
        assertThat(response.watch().searchTerm()).isEqualTo("air fryer");
    }
}
