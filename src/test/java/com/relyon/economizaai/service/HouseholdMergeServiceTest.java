package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.MergeCategory;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        lenient().when(receiptRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(shoppingListRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
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
        lenient().when(shoppingListRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

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
        lenient().when(shoppingListRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(manualPurchaseRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(consumptionSnoozeRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(categoryOverrideRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(customCategoryRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(productAliasRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(marketAliasRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        lenient().when(brandPreferenceRepository.findAllByOriginHouseholdId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        var restored = mergeService.restoreOriginals(origin);

        assertEquals(2, restored.moved());
        assertTrue(brought.stream().allMatch(r -> r.getHousehold().getId().equals(origin.getId())));
    }
}
