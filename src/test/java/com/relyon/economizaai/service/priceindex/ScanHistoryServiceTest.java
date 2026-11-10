package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductSnapshot;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdProductSnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanHistoryServiceTest {

    @Mock private HouseholdProductSnapshotRepository snapshotRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private PlatformTransactionManager transactionManager;

    private ScanHistoryService service;
    private UUID householdId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ScanHistoryService(snapshotRepository, productRepository,
                householdProductAliasService, transactionManager);
        householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).build();
        user = User.builder().id(UUID.randomUUID()).household(household).build();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void recordConsult_insertsNewSnapshotKeyedByProduct() {
        var productId = UUID.randomUUID();
        when(snapshotRepository.findByHouseholdIdAndSnapshotKey(householdId, productId.toString()))
                .thenReturn(Optional.empty());

        service.recordConsult(householdId, productId, "creme de leite", new BigDecimal("3.49"), "NEARBY");

        var saved = capture();
        assertThat(saved.getProductId()).isEqualTo(productId);
        assertThat(saved.getSnapshotKey()).isEqualTo(productId.toString());
        assertThat(saved.getLastBestUnitPrice()).isEqualByComparingTo("3.49");
        assertThat(saved.getLastBestScope()).isEqualTo("NEARBY");
        assertThat(saved.getScanCount()).isEqualTo(1);
        assertThat(saved.getLastConsultedAt()).isNotNull();
    }

    @Test
    void recordConsult_keyedByTypeWhenNoProduct() {
        when(snapshotRepository.findByHouseholdIdAndSnapshotKey(householdId, "type:arroz"))
                .thenReturn(Optional.empty());

        service.recordConsult(householdId, null, "arroz", new BigDecimal("5.00"), "STATE");

        var saved = capture();
        assertThat(saved.getProductId()).isNull();
        assertThat(saved.getSnapshotKey()).isEqualTo("type:arroz");
        assertThat(saved.getGenericNameNorm()).isEqualTo("arroz");
    }

    @Test
    void recordConsult_upsertBumpsCountAndUpdatesPrice() {
        var productId = UUID.randomUUID();
        var existing = HouseholdProductSnapshot.builder()
                .householdId(householdId).productId(productId).snapshotKey(productId.toString())
                .lastBestUnitPrice(new BigDecimal("4.00")).lastBestScope("STATE").scanCount(2)
                .lastConsultedAt(OffsetDateTime.now().minusDays(3)).build();
        when(snapshotRepository.findByHouseholdIdAndSnapshotKey(householdId, productId.toString()))
                .thenReturn(Optional.of(existing));

        service.recordConsult(householdId, productId, null, new BigDecimal("3.50"), "NEARBY");

        var saved = capture();
        assertThat(saved.getScanCount()).isEqualTo(3);
        assertThat(saved.getLastBestUnitPrice()).isEqualByComparingTo("3.50");
        assertThat(saved.getLastBestScope()).isEqualTo("NEARBY");
    }

    @Test
    void recordConsult_scanWithNullPriceDoesNotClobberExistingPrice() {
        var productId = UUID.randomUUID();
        var existing = HouseholdProductSnapshot.builder()
                .householdId(householdId).productId(productId).snapshotKey(productId.toString())
                .lastBestUnitPrice(new BigDecimal("4.00")).lastBestScope("STATE").scanCount(1)
                .lastConsultedAt(OffsetDateTime.now().minusDays(1)).build();
        when(snapshotRepository.findByHouseholdIdAndSnapshotKey(householdId, productId.toString()))
                .thenReturn(Optional.of(existing));

        // The barcode scan log records with null price — must keep the price a comparison captured.
        service.recordConsult(householdId, productId, null, null, null);

        var saved = capture();
        assertThat(saved.getLastBestUnitPrice()).isEqualByComparingTo("4.00");
        assertThat(saved.getLastBestScope()).isEqualTo("STATE");
        assertThat(saved.getScanCount()).isEqualTo(2);
    }

    @Test
    void recordConsult_noKeyWhenNeitherProductNorType_skips() {
        service.recordConsult(householdId, null, null, new BigDecimal("1.00"), "NEARBY");
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void recordConsult_swallowsFailure() {
        when(snapshotRepository.findByHouseholdIdAndSnapshotKey(any(), any()))
                .thenThrow(new RuntimeException("db down"));
        // Must not propagate — called from the read/scan path that can't break.
        service.recordConsult(householdId, UUID.randomUUID(), null, new BigDecimal("1.00"), "NEARBY");
    }

    @Test
    void listScannedProducts_dedupedNewestFirst_hydratesNames() {
        var productId = UUID.randomUUID();
        var withProduct = HouseholdProductSnapshot.builder()
                .householdId(householdId).productId(productId).snapshotKey(productId.toString())
                .genericNameNorm("creme de leite")
                .lastBestUnitPrice(new BigDecimal("3.49")).lastBestScope("NEARBY").scanCount(4)
                .lastConsultedAt(OffsetDateTime.now()).build();
        var typeOnly = HouseholdProductSnapshot.builder()
                .householdId(householdId).snapshotKey("type:arroz").genericNameNorm("arroz")
                .lastBestUnitPrice(new BigDecimal("5.00")).lastBestScope("STATE").scanCount(1)
                .lastConsultedAt(OffsetDateTime.now().minusDays(1)).build();
        when(snapshotRepository.findByHouseholdIdOrderByLastConsultedAtDesc(any(), any()))
                .thenReturn(List.of(withProduct, typeOnly));
        var product = Product.builder().id(productId).normalizedName("Creme de Leite Nestlé")
                .genericName("Creme de Leite").genericNameNorm("creme de leite").build();
        when(productRepository.findAllById(List.of(productId))).thenReturn(List.of(product));
        when(householdProductAliasService.findFor(any(), any())).thenReturn("Meu creme");

        var rows = service.listScannedProducts(user);

        assertThat(rows).hasSize(2);
        // Newest first = the one consulted now (the product row).
        assertThat(rows.get(0).productId()).isEqualTo(productId);
        assertThat(rows.get(0).productName()).isEqualTo("Meu creme");
        assertThat(rows.get(0).typeLabel()).isEqualTo("Creme de Leite");
        assertThat(rows.get(0).snapshotBestPrice()).isEqualByComparingTo("3.49");
        assertThat(rows.get(0).scanCount()).isEqualTo(4);
        // Type-only row has no product; name falls back to the type.
        assertThat(rows.get(1).productId()).isNull();
        assertThat(rows.get(1).typeLabel()).isEqualTo("arroz");
        assertThat(rows.get(1).productName()).isEqualTo("arroz");
    }

    @Test
    void listScannedProducts_emptyWhenNoSnapshots() {
        when(snapshotRepository.findByHouseholdIdOrderByLastConsultedAtDesc(any(), any()))
                .thenReturn(List.of());
        assertThat(service.listScannedProducts(user)).isEmpty();
    }

    private HouseholdProductSnapshot capture() {
        var captor = ArgumentCaptor.forClass(HouseholdProductSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        return captor.getValue();
    }
}
