package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.model.GarimpoPriceSnapshot;
import com.relyon.economizaai.repository.GarimpoPriceSnapshotRepository;
import com.relyon.economizaai.service.ecommerce.ProviderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarimpoSnapshotRecorderTest {

    @Mock
    private GarimpoPriceSnapshotRepository snapshotRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private GarimpoSnapshotRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new GarimpoSnapshotRecorder(snapshotRepository, transactionTemplate);
    }

    @SuppressWarnings("unchecked")
    private void runTransactionsInline() {
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private ProviderProduct product(String externalId, String price, String originalPrice) {
        return new ProviderProduct(
                "mercadolivre", externalId, "Fone Bluetooth XYZ",
                new BigDecimal(price),
                originalPrice == null ? null : new BigDecimal(originalPrice),
                originalPrice == null ? null : 33,
                "BRL", "https://ml/" + externalId, "https://ml/" + externalId + "?matt_tool=t",
                "https://img/" + externalId, "LOJA X", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFirstObservationOfAProduct() {
        runTransactionsInline();
        when(snapshotRepository.findFirstByProviderAndExternalIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());

        var written = recorder.recordChangedPrices(List.of(product("MLB111", "99.9", "149.90")));

        assertThat(written).isEqualTo(1);
        ArgumentCaptor<List<GarimpoPriceSnapshot>> savedSnapshots = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).saveAll(savedSnapshots.capture());
        var snapshot = savedSnapshots.getValue().get(0);
        // Money is scaled to 2 BEFORE persisting so memory matches NUMERIC(12,2).
        assertThat(snapshot.getPrice()).isEqualTo(new BigDecimal("99.90"));
        assertThat(snapshot.getOriginalPrice()).isEqualTo(new BigDecimal("149.90"));
        assertThat(snapshot.getDiscountPercent()).isEqualTo(33);
    }

    @Test
    void skipsProductWhosePriceDidNotChange() {
        when(snapshotRepository.findFirstByProviderAndExternalIdOrderByCreatedAtDesc("mercadolivre", "MLB111"))
                .thenReturn(Optional.of(GarimpoPriceSnapshot.builder()
                        .price(new BigDecimal("99.90")).build()));

        var written = recorder.recordChangedPrices(List.of(product("MLB111", "99.90", null)));

        assertThat(written).isZero();
        verify(snapshotRepository, never()).saveAll(any());
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void recordsPriceChange() {
        runTransactionsInline();
        when(snapshotRepository.findFirstByProviderAndExternalIdOrderByCreatedAtDesc("mercadolivre", "MLB111"))
                .thenReturn(Optional.of(GarimpoPriceSnapshot.builder()
                        .price(new BigDecimal("99.90")).build()));

        var written = recorder.recordChangedPrices(List.of(product("MLB111", "89.90", null)));

        assertThat(written).isEqualTo(1);
    }

    @Test
    void ignoresProductsWithoutExternalIdOrPrice() {
        var withoutId = new ProviderProduct("mercadolivre", null, "t", new BigDecimal("10.00"),
                null, null, "BRL", null, null, null, null, false);

        assertThat(recorder.recordChangedPrices(List.of(withoutId))).isZero();
        verify(snapshotRepository, never()).saveAll(any());
    }
}
