package com.relyon.economizaai.service;

import com.relyon.economizaai.model.BaseEntity;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.HouseholdScoped;
import com.relyon.economizaai.model.ManualBrandPreference;
import com.relyon.economizaai.model.ManualPurchase;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.ShoppingList;
import com.relyon.economizaai.model.ShoppingListItem;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Moves and copies household-scoped data between households.
 *
 * <p><b>Merge (join):</b> for each selected {@link MergeCategory}, move the joiner's
 * rows from their origin household into the target. Collision rule (host wins): if a
 * row's {@code collisionKey()} already exists in the target (the per-household UNIQUE
 * constraint would reject it), the TARGET's row is kept and the joiner's row is left
 * PARKED on its origin household (not moved, not deleted) so it can still be restored
 * on split. {@code originHousehold} is never rewritten — it's the provenance anchor.
 *
 * <p><b>Restore (split):</b> move back to a household every row whose
 * {@code originHousehold} is that household — i.e. each person reclaims exactly what
 * they brought, including parked-shadowed rows that never made it into the target.
 *
 * <p><b>Copy (split, rules 11/12):</b> duplicate into the leaver's home what the shared
 * household generated after they joined (or everything, for the original host). Copies
 * are new rows homed and originating in the destination; receipt copies never feed the
 * price index. Nobody who stays loses anything.
 *
 * <p><b>Evacuate (rule 15):</b> when the LAST member leaves, the household's data moves
 * to their home (no copy — nobody's left), duplicates by key are dropped, and the
 * household can be deleted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdMergeService {

    private final ReceiptRepository receiptRepository;
    private final ShoppingListRepository shoppingListRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final HouseholdProductCategoryOverrideRepository categoryOverrideRepository;
    private final HouseholdCustomCategoryRepository customCategoryRepository;
    private final HouseholdProductAliasRepository productAliasRepository;
    private final HouseholdMarketAliasRepository marketAliasRepository;
    private final ManualBrandPreferenceRepository brandPreferenceRepository;

    /** Only receipts that actually carry items are worth copying for a leaver. */
    private static final Set<ReceiptStatus> COPYABLE_RECEIPT_STATUSES =
            EnumSet.of(ReceiptStatus.CONFIRMED, ReceiptStatus.PENDING_CONFIRMATION);

    /** Outcome counts for one category's merge, for logging + the API response. */
    public record MergeResult(int moved, int shadowed) {
        MergeResult plus(MergeResult other) { return new MergeResult(moved + other.moved, shadowed + other.shadowed); }
        static MergeResult zero() { return new MergeResult(0, 0); }
    }

    // Binds a MergeCategory to its repo's "find rows currently in household X" finder
    // and its save-all, both typed to the concrete HouseholdScoped entity. One record
    // per category lets the generic algorithm run over any entity uniformly.
    private record Binding<T extends HouseholdScoped>(
            Function<UUID, List<T>> findByHousehold,
            Function<UUID, List<T>> findByOriginHousehold,
            Consumer<List<T>> saveAll,
            Consumer<List<T>> deleteAll) {}

    @SuppressWarnings("unchecked")
    private <T extends HouseholdScoped> Binding<T> binding(MergeCategory category) {
        return (Binding<T>) switch (category) {
            case RECEIPTS -> bind(receiptRepository::findAllByHouseholdId, receiptRepository::findAllByOriginHouseholdId, receiptRepository::saveAll, receiptRepository::deleteAll);
            case SHOPPING_LISTS -> bind(shoppingListRepository::findAllByHouseholdId, shoppingListRepository::findAllByOriginHouseholdId, shoppingListRepository::saveAll, shoppingListRepository::deleteAll);
            case MANUAL_PURCHASES -> bind(manualPurchaseRepository::findAllByHouseholdId, manualPurchaseRepository::findAllByOriginHouseholdId, manualPurchaseRepository::saveAll, manualPurchaseRepository::deleteAll);
            case CONSUMPTION_SNOOZES -> bind(consumptionSnoozeRepository::findAllByHouseholdId, consumptionSnoozeRepository::findAllByOriginHouseholdId, consumptionSnoozeRepository::saveAll, consumptionSnoozeRepository::deleteAll);
            case CATEGORY_OVERRIDES -> bind(categoryOverrideRepository::findAllByHouseholdId, categoryOverrideRepository::findAllByOriginHouseholdId, categoryOverrideRepository::saveAll, categoryOverrideRepository::deleteAll);
            case CUSTOM_CATEGORIES -> bind(customCategoryRepository::findAllByHouseholdId, customCategoryRepository::findAllByOriginHouseholdId, customCategoryRepository::saveAll, customCategoryRepository::deleteAll);
            case PRODUCT_ALIASES -> bind(productAliasRepository::findAllByHouseholdId, productAliasRepository::findAllByOriginHouseholdId, productAliasRepository::saveAll, productAliasRepository::deleteAll);
            case MARKET_ALIASES -> bind(marketAliasRepository::findAllByHouseholdId, marketAliasRepository::findAllByOriginHouseholdId, marketAliasRepository::saveAll, marketAliasRepository::deleteAll);
            case BRAND_PREFERENCES -> bind(brandPreferenceRepository::findAllByHouseholdId, brandPreferenceRepository::findAllByOriginHouseholdId, brandPreferenceRepository::saveAll, brandPreferenceRepository::deleteAll);
        };
    }

    // Each repo exposes the same finders + saveAll/deleteAll; wire them as method refs so
    // the binding is compile-checked (no reflection) and the generic algorithm is uniform.
    private <T extends HouseholdScoped> Binding<T> bind(Function<UUID, List<T>> byHousehold,
                                                        Function<UUID, List<T>> byOrigin,
                                                        Function<List<T>, ?> saveAll,
                                                        Consumer<? super Iterable<T>> deleteAll) {
        return new Binding<>(byHousehold, byOrigin, rows -> saveAll.apply(rows), deleteAll::accept);
    }

    // ---------------------------------------------------------------- merge (join)

    /**
     * Merge the joiner's selected categories from {@code origin} into {@code target}.
     * Returns the aggregate moved/shadowed counts. Runs inside the caller's transaction
     * (HouseholdService.join is @Transactional), so a failure rolls the whole join back.
     */
    public MergeResult merge(Household origin, Household target, Set<MergeCategory> categories) {
        var total = MergeResult.zero();
        for (var category : categories) {
            var result = mergeCategory(category, origin, target);
            total = total.plus(result);
            log.info("merge.category category={} moved={} shadowed={} origin={} target={}",
                    category, result.moved(), result.shadowed(), origin.getId(), target.getId());
        }
        return total;
    }

    private <T extends HouseholdScoped> MergeResult mergeCategory(MergeCategory category, Household origin, Household target) {
        Binding<T> binding = binding(category);
        // Keys already present in the target — these collide (host wins).
        Set<String> targetKeys = collectKeys(binding.findByHousehold().apply(target.getId()));
        // Within the joiner's own batch, the first occurrence of a key wins; later
        // dupes are shadowed too (can't insert two identical keys into the target).
        Set<String> claimed = new HashSet<>(targetKeys);

        var toMove = new ArrayList<T>();
        int shadowed = 0;
        for (T row : binding.findByHousehold().apply(origin.getId())) {
            String key = row.collisionKey();
            if (key != null && claimed.contains(key)) {
                // Collision: keep target's, leave this one parked on its origin
                // (do NOT move it). Its originHousehold already points home, so split
                // can restore it later.
                shadowed++;
                continue;
            }
            if (key != null) {
                claimed.add(key);
            }
            row.setHousehold(target);   // origin_household_id stays untouched
            toMove.add(row);
        }
        if (!toMove.isEmpty()) {
            binding.saveAll().accept(toMove);
        }
        return new MergeResult(toMove.size(), shadowed);
    }

    // ---------------------------------------------------------------- restore (split)

    /**
     * Restore to {@code home} every row (across ALL categories) whose origin household
     * is {@code home} but currently lives elsewhere — used on split so each person
     * reclaims exactly what they brought, including rows that were shadowed (parked)
     * during the merge.
     */
    public MergeResult restoreOriginals(Household home) {
        var total = MergeResult.zero();
        for (var category : MergeCategory.values()) {
            total = total.plus(restoreCategory(category, home));
        }
        return total;
    }

    private <T extends HouseholdScoped> MergeResult restoreCategory(MergeCategory category, Household home) {
        Binding<T> binding = binding(category);
        var toRestore = new ArrayList<T>();
        for (T row : binding.findByOriginHousehold().apply(home.getId())) {
            if (!row.getHousehold().getId().equals(home.getId())) {
                row.setHousehold(home);   // bring it back home
                toRestore.add(row);
            }
        }
        if (!toRestore.isEmpty()) {
            binding.saveAll().accept(toRestore);
            log.info("merge.restore category={} restored={} home={}", category, toRestore.size(), home.getId());
        }
        return new MergeResult(toRestore.size(), 0);
    }

    // ---------------------------------------------------------------- copy (split, rules 11/12)

    /**
     * Copy into {@code destination} what {@code shared} generated at or after {@code since}
     * (null = everything): receipts with their items, shopping lists with their items,
     * custom categories, category overrides, product/market friendly names, brand
     * preferences and manual purchases. Rows whose key already exists in the destination
     * are skipped (the destination's version wins). Receipt copies are flagged
     * {@code indexEligible=false} and point at their source. Returns the number of rows copied.
     */
    public int copyGeneratedSince(Household shared, Household destination, LocalDateTime since) {
        var copied = 0;
        var categories = copyCustomCategories(shared, destination, since);
        copied += categories.copied();
        copied += copyReceipts(shared, destination, since);
        copied += copyShoppingLists(shared, destination, since);
        copied += copyCategoryOverrides(shared, destination, since, categories.bySourceId());
        copied += copyProductAliases(shared, destination, since);
        copied += copyMarketAliases(shared, destination, since);
        copied += copyBrandPreferences(shared, destination, since);
        copied += copyManualPurchases(shared, destination, since);
        log.info("merge.copy shared={} destination={} since={} copied={}", shared.getId(), destination.getId(), since, copied);
        return copied;
    }

    private static boolean generatedSince(BaseEntity entity, LocalDateTime since) {
        if (since == null) return true;
        var createdAt = entity.getCreatedAt();
        return createdAt == null || !createdAt.isBefore(since);
    }

    /** Source custom-category id → its counterpart in the destination (copied or pre-existing by name). */
    private record CategoryCopy(Map<UUID, HouseholdCustomCategory> bySourceId, int copied) {}

    private int copyReceipts(Household shared, Household destination, LocalDateTime since) {
        var existing = collectKeys(receiptRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<Receipt>();
        for (var source : receiptRepository.findAllByHouseholdId(shared.getId())) {
            if (!COPYABLE_RECEIPT_STATUSES.contains(source.getStatus())) continue;
            if (!generatedSince(source, since)) continue;
            if (existing.contains(source.getChaveAcesso())) continue;
            existing.add(source.getChaveAcesso());
            copies.add(copyReceipt(source, destination));
        }
        if (!copies.isEmpty()) receiptRepository.saveAll(copies);
        return copies.size();
    }

    // Full copy, items included; the scanner stays the same person. Raw HTML is left
    // behind (bulky, and the original keeps it) — the copy is for history, not reparsing.
    private Receipt copyReceipt(Receipt source, Household destination) {
        var copy = Receipt.builder()
                .user(source.getUser())
                .household(destination)
                .originHousehold(destination)
                .chaveAcesso(source.getChaveAcesso())
                .origin(source.getOrigin())
                .entryMethod(source.getEntryMethod())
                .channel(source.getChannel())
                .uf(source.getUf())
                .cnpjEmitente(source.getCnpjEmitente())
                .marketName(source.getMarketName())
                .marketAddress(source.getMarketAddress())
                .issuedAt(source.getIssuedAt())
                .totalAmount(source.getTotalAmount())
                .discountTotal(source.getDiscountTotal())
                .approxTaxFederal(source.getApproxTaxFederal())
                .approxTaxEstadual(source.getApproxTaxEstadual())
                .qrPayload(source.getQrPayload())
                .sourceUrl(source.getSourceUrl())
                .status(source.getStatus())
                .confirmedAt(source.getConfirmedAt())
                .indexEligible(false)
                .copiedFromReceiptId(source.getId())
                .build();
        for (var item : source.getItems()) {
            copy.addItem(ReceiptItem.builder()
                    .product(item.getProduct())
                    .lineNumber(item.getLineNumber())
                    .rawDescription(item.getRawDescription())
                    .friendlyDescription(item.getFriendlyDescription())
                    .ean(item.getEan())
                    .quantity(item.getQuantity())
                    .unit(item.getUnit())
                    .unitPrice(item.getUnitPrice())
                    .totalPrice(item.getTotalPrice())
                    .excluded(item.isExcluded())
                    .excludedFromPersonal(item.isExcludedFromPersonal())
                    .nfcePromoFlag(item.isNfcePromoFlag())
                    .paidUnitPrice(item.getPaidUnitPrice())
                    .paidTotalPrice(item.getPaidTotalPrice())
                    .categoryAtConfirmation(item.getCategoryAtConfirmation())
                    .categoryNodeAtConfirmation(item.getCategoryNodeAtConfirmation())
                    .aiCategoryId(item.getAiCategoryId())
                    .build());
        }
        return copy;
    }

    private int copyShoppingLists(Household shared, Household destination, LocalDateTime since) {
        var copies = new ArrayList<ShoppingList>();
        for (var source : shoppingListRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since)) continue;
            var copy = ShoppingList.builder()
                    .household(destination)
                    .originHousehold(destination)
                    .createdBy(source.getCreatedBy())
                    .name(source.getName())
                    .build();
            for (var item : source.getItems()) {
                copy.addItem(ShoppingListItem.builder()
                        .product(item.getProduct())
                        .freeText(item.getFreeText())
                        .quantity(item.getQuantity())
                        .position(item.getPosition())
                        .checked(item.isChecked())
                        .checkedAt(item.getCheckedAt())
                        .build());
            }
            copies.add(copy);
        }
        if (!copies.isEmpty()) shoppingListRepository.saveAll(copies);
        return copies.size();
    }

    /** Copies custom categories (parents first); the mapping lets overrides re-point to the destination's categories. */
    private CategoryCopy copyCustomCategories(Household shared, Household destination, LocalDateTime since) {
        var existingByName = new HashMap<String, HouseholdCustomCategory>();
        for (var category : customCategoryRepository.findAllByHouseholdId(destination.getId())) {
            existingByName.put(category.getName().toLowerCase(), category);
        }
        var sources = customCategoryRepository.findAllByHouseholdId(shared.getId());
        var mapped = new HashMap<UUID, HouseholdCustomCategory>();
        var copies = new ArrayList<HouseholdCustomCategory>();
        // Two passes: roots first, then children (their parent copy must already exist).
        for (var pass = 0; pass < 2; pass++) {
            for (var source : sources) {
                if (mapped.containsKey(source.getId())) continue;
                var parent = source.getParentCustomCategory();
                if ((pass == 0) != (parent == null)) continue;
                var already = existingByName.get(source.getName().toLowerCase());
                if (already != null) {
                    mapped.put(source.getId(), already);   // destination's version wins
                    continue;
                }
                if (!generatedSince(source, since)) continue;
                var copy = HouseholdCustomCategory.builder()
                        .household(destination)
                        .originHousehold(destination)
                        .name(source.getName())
                        .icon(source.getIcon())
                        .color(source.getColor())
                        .parentCategory(source.getParentCategory())
                        .parentCustomCategory(parent == null ? null : mapped.get(parent.getId()))
                        .build();
                existingByName.put(copy.getName().toLowerCase(), copy);
                mapped.put(source.getId(), copy);
                copies.add(copy);
            }
        }
        if (!copies.isEmpty()) customCategoryRepository.saveAll(copies);
        return new CategoryCopy(mapped, copies.size());
    }

    private int copyCategoryOverrides(Household shared, Household destination, LocalDateTime since,
                                      Map<UUID, HouseholdCustomCategory> categoryMap) {
        var existing = collectKeys(categoryOverrideRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<HouseholdProductCategoryOverride>();
        for (var source : categoryOverrideRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since)) continue;
            var key = source.collisionKey();
            if (key != null && existing.contains(key)) continue;
            HouseholdCustomCategory customCategory = null;
            if (source.getCustomCategory() != null) {
                customCategory = categoryMap.get(source.getCustomCategory().getId());
                if (customCategory == null) continue;   // its category wasn't copied — nothing to point at
            }
            if (key != null) existing.add(key);
            copies.add(HouseholdProductCategoryOverride.builder()
                    .household(destination)
                    .originHousehold(destination)
                    .product(source.getProduct())
                    .category(source.getCategory())
                    .customCategory(customCategory)
                    .categoryNodeId(source.getCategoryNodeId())
                    .build());
        }
        if (!copies.isEmpty()) categoryOverrideRepository.saveAll(copies);
        return copies.size();
    }

    private int copyProductAliases(Household shared, Household destination, LocalDateTime since) {
        var existing = collectKeys(productAliasRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<HouseholdProductAlias>();
        for (var source : productAliasRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since) || existing.contains(source.collisionKey())) continue;
            existing.add(source.collisionKey());
            copies.add(HouseholdProductAlias.builder()
                    .household(destination).originHousehold(destination)
                    .product(source.getProduct()).friendlyName(source.getFriendlyName())
                    .build());
        }
        if (!copies.isEmpty()) productAliasRepository.saveAll(copies);
        return copies.size();
    }

    private int copyMarketAliases(Household shared, Household destination, LocalDateTime since) {
        var existing = collectKeys(marketAliasRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<HouseholdMarketAlias>();
        for (var source : marketAliasRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since) || existing.contains(source.collisionKey())) continue;
            existing.add(source.collisionKey());
            copies.add(HouseholdMarketAlias.builder()
                    .household(destination).originHousehold(destination)
                    .marketCnpj(source.getMarketCnpj()).customName(source.getCustomName())
                    .build());
        }
        if (!copies.isEmpty()) marketAliasRepository.saveAll(copies);
        return copies.size();
    }

    private int copyBrandPreferences(Household shared, Household destination, LocalDateTime since) {
        var existing = collectKeys(brandPreferenceRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<ManualBrandPreference>();
        for (var source : brandPreferenceRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since) || existing.contains(source.collisionKey())) continue;
            existing.add(source.collisionKey());
            copies.add(ManualBrandPreference.builder()
                    .household(destination).originHousehold(destination)
                    .genericName(source.getGenericName()).brand(source.getBrand()).strength(source.getStrength())
                    .build());
        }
        if (!copies.isEmpty()) brandPreferenceRepository.saveAll(copies);
        return copies.size();
    }

    private int copyManualPurchases(Household shared, Household destination, LocalDateTime since) {
        var copies = new ArrayList<ManualPurchase>();
        for (var source : manualPurchaseRepository.findAllByHouseholdId(shared.getId())) {
            if (!generatedSince(source, since)) continue;
            copies.add(ManualPurchase.builder()
                    .household(destination).originHousehold(destination)
                    .user(source.getUser()).product(source.getProduct())
                    .quantity(source.getQuantity()).purchasedAt(source.getPurchasedAt())
                    .build());
        }
        if (!copies.isEmpty()) manualPurchaseRepository.saveAll(copies);
        return copies.size();
    }

    /**
     * Consent-approved copy (legacy flow, no longer reachable from the app — the split
     * rules above copy without asking): duplicate the grantor's own receipts currently in
     * {@code shared} into {@code destination}, items included, skipping chaves already
     * there. Returns how many were copied.
     */
    public int copyUserData(UUID grantorUserId, Household shared, Household destination) {
        var source = receiptRepository.findAllByUserIdAndHouseholdId(grantorUserId, shared.getId());
        if (source.isEmpty()) {
            return 0;
        }
        var existing = collectKeys(receiptRepository.findAllByHouseholdId(destination.getId()));
        var copies = new ArrayList<Receipt>();
        for (var receipt : source) {
            if (existing.contains(receipt.getChaveAcesso())) continue;
            existing.add(receipt.getChaveAcesso());
            copies.add(copyReceipt(receipt, destination));
        }
        if (!copies.isEmpty()) {
            receiptRepository.saveAll(copies);
            log.info("consent.copy grantor={} copied={} shared={} destination={}",
                    grantorUserId, copies.size(), shared.getId(), destination.getId());
        }
        return copies.size();
    }

    // ---------------------------------------------------------------- evacuate (rule 15)

    /**
     * The last member is leaving {@code emptying}: MOVE everything to {@code destination}
     * (nobody stays, so no copy) and drop what collides by key — "se a mesma nota existir
     * nos dois lados, fica uma só". Afterwards the household holds no data and can be deleted.
     * Returns moved/dropped counts.
     */
    public MergeResult evacuate(Household emptying, Household destination) {
        var moved = merge(emptying, destination, EnumSet.allOf(MergeCategory.class));
        var dropped = 0;
        for (var category : MergeCategory.values()) {
            dropped += deleteLeftovers(category, emptying);
        }
        log.info("merge.evacuate emptying={} destination={} moved={} dropped={}",
                emptying.getId(), destination.getId(), moved.moved(), dropped);
        return new MergeResult(moved.moved(), dropped);
    }

    private <T extends HouseholdScoped> int deleteLeftovers(MergeCategory category, Household household) {
        Binding<T> binding = binding(category);
        var leftovers = binding.findByHousehold().apply(household.getId());
        if (!leftovers.isEmpty()) {
            binding.deleteAll().accept(leftovers);
        }
        return leftovers.size();
    }

    // ---------------------------------------------------------------- helpers

    private <T extends HouseholdScoped> Set<String> collectKeys(List<T> rows) {
        var keys = new HashSet<String>();
        for (T row : rows) {
            var key = row.collisionKey();
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }
}
