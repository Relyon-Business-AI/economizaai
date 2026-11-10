package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.MergeCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ShoppingListRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class HouseholdMergeServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ShoppingListRepository shoppingListRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ConsumptionSnoozeRepository consumptionSnoozeRepository;
    @Mock private HouseholdProductCategoryOverrideRepository categoryOverrideRepository;
    @Mock private HouseholdCustomCategoryRepository customCategoryRepository;
    @Mock private HouseholdProductAliasRepository productAliasRepository;
    @Mock private HouseholdMarketAliasRepository marketAliasRepository;
    @Mock private ManualBrandPreferenceRepository brandPreferenceRepository;

    @InjectMocks private HouseholdMergeService mergeService;

    private Household household(String code) {
        return Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
    }

    private Receipt receipt(String chave, Household current, Household origin) {
        return Receipt.builder().id(UUID.randomUUID()).chaveAcesso(chave)
                .household(current).originHousehold(origin).build();
    }

    // Every category's "find by household" returns empty unless a test stubs it, so
    // merge(ALL) doesn't NPE iterating all nine categories. lenient = unused is fine.
    private void stubAllEmpty() {
        lenient().when(receiptRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(shoppingListRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByHouseholdId(any())).thenReturn(List.of());
    }

    @Test
    void merge_movesAllRowsWhenNoCollision() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        var r1 = receipt("CH1", origin, origin);
        var r2 = receipt("CH2", origin, origin);
        when(receiptRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(receiptRepository.findAllByHouseholdId(origin.getId())).thenReturn(List.of(r1, r2));

        var result = mergeService.merge(origin, target, Set.of(MergeCategory.RECEIPTS));

        assertEquals(2, result.moved());
        assertEquals(0, result.shadowed());
        // both receipts now point at the target as CURRENT household...
        assertEquals(target.getId(), r1.getHousehold().getId());
        assertEquals(target.getId(), r2.getHousehold().getId());
        // ...but their ORIGIN is untouched (still the origin household).
        assertEquals(origin.getId(), r1.getOriginHousehold().getId());
    }

    @Test
    void merge_shadowsCollidingRowAndKeepsTargetsCopy() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        var targetExisting = receipt("DUP", target, target);
        var joinerDup = receipt("DUP", origin, origin);   // same chave -> collision
        var joinerNew = receipt("UNIQUE", origin, origin);
        when(receiptRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(targetExisting));
        when(receiptRepository.findAllByHouseholdId(origin.getId())).thenReturn(List.of(joinerDup, joinerNew));

        var result = mergeService.merge(origin, target, Set.of(MergeCategory.RECEIPTS));

        assertEquals(1, result.moved());     // only the unique one moves
        assertEquals(1, result.shadowed());  // the dup is parked
        // the colliding joiner receipt stays on its origin household (not moved)
        assertEquals(origin.getId(), joinerDup.getHousehold().getId());
        // the unique one moved to target
        assertEquals(target.getId(), joinerNew.getHousehold().getId());

        // only the moved row is saved
        ArgumentCaptor<List<Receipt>> captor = ArgumentCaptor.forClass(List.class);
        verify(receiptRepository).saveAll(captor.capture());
        assertEquals(List.of(joinerNew), captor.getValue());
    }

    @Test
    void merge_dedupsWithinJoinerBatchAgainstSameKey() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        // two of the joiner's OWN rows share a key (shouldn't happen per-household, but
        // be defensive): first wins, second is shadowed.
        var a = receipt("SAME", origin, origin);
        var b = receipt("SAME", origin, origin);
        when(receiptRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(receiptRepository.findAllByHouseholdId(origin.getId())).thenReturn(List.of(a, b));

        var result = mergeService.merge(origin, target, Set.of(MergeCategory.RECEIPTS));

        assertEquals(1, result.moved());
        assertEquals(1, result.shadowed());
    }

    @Test
    void merge_emptyOriginMovesNothingAndSavesNothing() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        when(receiptRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(receiptRepository.findAllByHouseholdId(origin.getId())).thenReturn(List.of());

        var result = mergeService.merge(origin, target, Set.of(MergeCategory.RECEIPTS));

        assertEquals(0, result.moved());
        assertEquals(0, result.shadowed());
        verify(receiptRepository, never()).saveAll(anyList());
    }

    @Test
    void merge_allCategoriesIteratesEveryTypeWithoutError() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        stubAllEmpty();

        var result = mergeService.merge(origin, target, EnumSet.allOf(MergeCategory.class));

        assertEquals(0, result.moved());
        assertEquals(0, result.shadowed());
    }

    @Test
    void restoreOriginals_bringsBackRowsThatLiveElsewhere() {
        var home = household("HOME01");
        var shared = household("SHARED1");
        // a receipt that originated in home but currently lives in shared (was merged)
        var parkedAway = receipt("CH1", shared, home);
        // one already at home — must NOT be re-saved
        var alreadyHome = receipt("CH2", home, home);
        when(receiptRepository.findAllByOriginHouseholdId(home.getId()))
                .thenReturn(List.of(parkedAway, alreadyHome));
        // other categories empty
        lenient().when(shoppingListRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());

        var result = mergeService.restoreOriginals(home);

        assertEquals(1, result.moved());   // only the parked-away one is brought back
        assertEquals(home.getId(), parkedAway.getHousehold().getId());

        ArgumentCaptor<List<Receipt>> captor = ArgumentCaptor.forClass(List.class);
        verify(receiptRepository).saveAll(captor.capture());
        assertEquals(List.of(parkedAway), captor.getValue());
    }

    @Test
    void mergeThenRestore_roundTripsTheJoinersData() {
        var origin = household("ORIG01");
        var target = household("TARG01");
        var brought = new ArrayList<>(List.of(receipt("CH1", origin, origin), receipt("CH2", origin, origin)));

        // --- merge: both move to target ---
        when(receiptRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(receiptRepository.findAllByHouseholdId(origin.getId())).thenReturn(brought);
        mergeService.merge(origin, target, Set.of(MergeCategory.RECEIPTS));
        assertTrue(brought.stream().allMatch(r -> r.getHousehold().getId().equals(target.getId())));

        // --- restore to origin: both come home (origin still points at origin) ---
        when(receiptRepository.findAllByOriginHouseholdId(origin.getId())).thenReturn(brought);
        lenient().when(shoppingListRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByOriginHouseholdId(any())).thenReturn(List.of());

        var restored = mergeService.restoreOriginals(origin);

        assertEquals(2, restored.moved());
        assertTrue(brought.stream().allMatch(r -> r.getHousehold().getId().equals(origin.getId())));
    }

    // ---------- copyGeneratedSince (split rules 11/12) ----------

    private Receipt confirmedReceipt(String chave, Household home, LocalDateTime createdAt) {
        var receipt = Receipt.builder().id(UUID.randomUUID()).chaveAcesso(chave)
                .household(home).originHousehold(home).status(ReceiptStatus.CONFIRMED)
                .marketName("Mercado " + chave).indexEligible(true).build();
        receipt.setCreatedAt(createdAt);
        receipt.addItem(ReceiptItem.builder().lineNumber(1).rawDescription("ARROZ").quantity(BigDecimal.ONE)
                .totalPrice(new BigDecimal("9.90")).build());
        return receipt;
    }

    @Test
    void copyGeneratedSince_copiesOnlyReceiptsCreatedAfterJoining_withItems_notIndexEligible() {
        stubAllEmpty();
        var shared = household("SHR001");
        var home = household("HOM001");
        var joinedAt = LocalDateTime.of(2026, 6, 1, 0, 0);
        var before = confirmedReceipt("OLD", shared, joinedAt.minusDays(1));
        var after = confirmedReceipt("NEW", shared, joinedAt.plusDays(1));
        var processing = Receipt.builder().id(UUID.randomUUID()).chaveAcesso("PRC").household(shared)
                .originHousehold(shared).status(ReceiptStatus.PROCESSING).build();
        processing.setCreatedAt(joinedAt.plusDays(2));
        when(receiptRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(before, after, processing));
        when(receiptRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of());

        var copied = mergeService.copyGeneratedSince(shared, home, joinedAt);

        assertEquals(1, copied);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Receipt>> saved = ArgumentCaptor.forClass(List.class);
        verify(receiptRepository).saveAll(saved.capture());
        var copy = saved.getValue().get(0);
        assertEquals("NEW", copy.getChaveAcesso());
        assertEquals(home.getId(), copy.getHousehold().getId());
        assertEquals(home.getId(), copy.getOriginHousehold().getId());
        assertEquals(after.getId(), copy.getCopiedFromReceiptId());
        assertFalse(copy.isIndexEligible(), "copies never feed the price index");
        assertEquals(1, copy.getItems().size());
        assertEquals("ARROZ", copy.getItems().get(0).getRawDescription());
        assertNotSame(after.getItems().get(0), copy.getItems().get(0));
        // the original is untouched
        assertEquals(shared.getId(), after.getHousehold().getId());
        assertTrue(after.isIndexEligible());
    }

    @Test
    void copyGeneratedSince_nullSince_copiesEverythingButSkipsChavesAlreadyAtHome() {
        stubAllEmpty();
        var shared = household("SHR001");
        var home = household("HOM001");
        var old = confirmedReceipt("OLD", shared, LocalDateTime.of(2025, 1, 1, 0, 0));
        var dup = confirmedReceipt("DUP", shared, LocalDateTime.of(2026, 1, 1, 0, 0));
        var alreadyHome = confirmedReceipt("DUP", home, LocalDateTime.of(2026, 2, 2, 0, 0));
        when(receiptRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(old, dup));
        when(receiptRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of(alreadyHome));

        var copied = mergeService.copyGeneratedSince(shared, home, null);

        assertEquals(1, copied);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Receipt>> saved = ArgumentCaptor.forClass(List.class);
        verify(receiptRepository).saveAll(saved.capture());
        assertEquals("OLD", saved.getValue().get(0).getChaveAcesso());
    }

    @Test
    void copyGeneratedSince_nothingToCopy_savesNothing() {
        stubAllEmpty();
        var shared = household("SHR001");
        var home = household("HOM001");

        assertEquals(0, mergeService.copyGeneratedSince(shared, home, LocalDateTime.now()));

        verify(receiptRepository, never()).saveAll(anyList());
        verify(shoppingListRepository, never()).saveAll(anyList());
        verify(customCategoryRepository, never()).saveAll(anyList());
    }

    // ---------- evacuate (rule 15) ----------

    @Test
    void evacuate_movesEverythingHomeAndDeletesWhatCollided() {
        stubAllEmpty();
        var emptying = household("EMP001");
        var home = household("HOM001");
        var unique = receipt("UNQ", emptying, emptying);
        var dup = receipt("DUP", emptying, emptying);
        var alreadyHome = receipt("DUP", home, home);
        // merge pass sees both; after it, the collided one is still "in" the emptying household
        when(receiptRepository.findAllByHouseholdId(emptying.getId()))
                .thenReturn(List.of(unique, dup))
                .thenReturn(List.of(dup));
        when(receiptRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of(alreadyHome));

        var result = mergeService.evacuate(emptying, home);

        assertEquals(1, result.moved());
        assertEquals(1, result.shadowed(), "the duplicate is dropped, not kept twice");
        assertEquals(home.getId(), unique.getHousehold().getId());
        verify(receiptRepository).deleteAll(List.of(dup));
    }
}
