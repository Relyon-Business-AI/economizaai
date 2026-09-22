package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductAliasServiceTest {

    @Mock private HouseholdProductAliasRepository repository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @InjectMocks private HouseholdProductAliasService service;

    private Household household() {
        return Household.builder().id(UUID.randomUUID()).build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("arroz").build();
    }

    @Test
    void rememberFromItem_nullProduct_doesNothing() {
        var item = ReceiptItem.builder().friendlyDescription("Arroz do bom").build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_nullFriendlyDescription_doesNothing() {
        var item = ReceiptItem.builder().product(product()).friendlyDescription(null).build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_blankFriendlyDescription_doesNothing() {
        var item = ReceiptItem.builder().product(product()).friendlyDescription("   ").build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_validItem_upsertsAlias() {
        var household = household();
        var product = product();
        var item = ReceiptItem.builder().product(product).friendlyDescription("Arroz do bom").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.rememberFromItem(household, item);

        var captor = ArgumentCaptor.forClass(HouseholdProductAlias.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("Arroz do bom", saved.getFriendlyName());
        assertEquals(product.getId(), saved.getProduct().getId());
        assertEquals(household.getId(), saved.getHousehold().getId());
    }

    @Test
    void upsert_createsWhenAbsent() {
        var household = household();
        var product = product();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.upsert(household, product, "Feijao preto");

        var captor = ArgumentCaptor.forClass(HouseholdProductAlias.class);
        verify(repository).save(captor.capture());
        assertEquals("Feijao preto", captor.getValue().getFriendlyName());
    }

    @Test
    void upsert_updatesExisting() {
        var household = household();
        var product = product();
        var existing = HouseholdProductAlias.builder()
                .household(household).product(product).friendlyName("old name").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.of(existing));

        service.upsert(household, product, "new name");

        verify(repository).save(existing);
        assertEquals("new name", existing.getFriendlyName());
    }

    @Test
    void findFor_nullProduct_returnsNull() {
        var result = service.findFor(household(), null);

        assertNull(result);
        verify(repository, never()).findByHouseholdIdAndProductId(any(), any());
    }

    @Test
    void findFor_presentAlias_returnsFriendlyName() {
        var household = household();
        var product = product();
        var alias = HouseholdProductAlias.builder()
                .household(household).product(product).friendlyName("Cafe forte").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.of(alias));

        assertSame("Cafe forte", service.findFor(household, product));
    }

    @Test
    void findFor_absentAlias_returnsNull() {
        var household = household();
        var product = product();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        assertNull(service.findFor(household, product));
    }

    @Test
    void resolvedNamesFor_emptyIds_returnsEmptyMapWithoutQuerying() {
        var result = service.resolvedNamesFor(UUID.randomUUID(), List.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(repository, receiptItemRepository);
    }

    @Test
    void resolvedNamesFor_explicitAliasTakesPrecedenceOverReceiptName() {
        var householdId = UUID.randomUUID();
        var product = product();
        var alias = HouseholdProductAlias.builder().product(product).friendlyName("Arroz da casa").build();
        when(repository.findAllByHouseholdIdAndProductIdIn(eq(householdId), anyList()))
                .thenReturn(List.of(alias));
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{product.getId(), "Arroz da nota"}));

        var result = service.resolvedNamesFor(householdId, List.of(product.getId()));

        assertEquals("Arroz da casa", result.get(product.getId()));
    }

    @Test
    void resolvedNamesFor_noAlias_fallsBackToLatestConfirmedReceiptFriendlyName() {
        var householdId = UUID.randomUUID();
        var product = product();
        when(repository.findAllByHouseholdIdAndProductIdIn(eq(householdId), anyList()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{product.getId(), "Arroz da nota"}));

        var result = service.resolvedNamesFor(householdId, List.of(product.getId()));

        assertEquals("Arroz da nota", result.get(product.getId()));
    }

    @Test
    void resolvedNamesFor_neitherAliasNorReceiptName_omitsProduct() {
        var householdId = UUID.randomUUID();
        var product = product();
        when(repository.findAllByHouseholdIdAndProductIdIn(eq(householdId), anyList()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(householdId)))
                .thenReturn(List.of());

        var result = service.resolvedNamesFor(householdId, List.of(product.getId()));

        assertEquals(Map.of(), result);
    }

    @Test
    void resolvedNameFor_nullProductId_returnsNullWithoutQuerying() {
        assertNull(service.resolvedNameFor(UUID.randomUUID(), null));
        verifyNoInteractions(repository, receiptItemRepository);
    }

    @Test
    void resolvedNameFor_delegatesToBatchResolution() {
        var householdId = UUID.randomUUID();
        var product = product();
        when(repository.findAllByHouseholdIdAndProductIdIn(eq(householdId), anyList()))
                .thenReturn(List.of());
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(anyList(), eq(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{product.getId(), "Arroz da nota"}));

        assertEquals("Arroz da nota", service.resolvedNameFor(householdId, product.getId()));
    }
}
