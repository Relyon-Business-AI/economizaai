package com.relyon.economizaai.service;

import com.relyon.economizaai.model.BaseEntity;
import com.relyon.economizaai.model.ConsumptionSnooze;
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
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ProductScanRepository;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * What happens to a shared household's data when someone leaves it.
 *
 * <p><b>Copy</b> ({@link #copyInto}): the leaver takes NEW rows that duplicate the
 * shared household's data (everything, or only what was created since they joined).
 * The household they leave keeps its originals — nobody loses data. Copied receipts
 * are marked {@code indexEligible=false} and never go through confirmation, so the
 * same price is never counted twice in the collaborative index.
 *
 * <p><b>Absorb</b> ({@link #absorbInto}): the LAST member leaves, so the household's
 * rows MOVE into their home and the emptied household is deleted. On a collision the
 * home's row wins and the duplicate is deleted, after its index trail is re-pointed.
 *
 * <p>Runs inside the caller's transaction (HouseholdService), so a failure rolls the
 * whole leave back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdSplitService {

    // In-flight or failed notas stay with the household that owns the pipeline state;
    // only finished history is worth taking along.
    private static final Set<ReceiptStatus> COPYABLE_RECEIPT_STATUSES =
            EnumSet.of(ReceiptStatus.CONFIRMED, ReceiptStatus.PENDING_CONFIRMATION);

    private final HouseholdRepository householdRepository;
    private final ReceiptRepository receiptRepository;
    private final ShoppingListRepository shoppingListRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final HouseholdProductCategoryOverrideRepository categoryOverrideRepository;
    private final HouseholdCustomCategoryRepository customCategoryRepository;
    private final HouseholdProductAliasRepository productAliasRepository;
    private final HouseholdMarketAliasRepository marketAliasRepository;
    private final ManualBrandPreferenceRepository brandPreferenceRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;
    private final ProductScanRepository productScanRepository;

    /** Rows copied, for logging. */
    public record CopyResult(int receipts, int shoppingLists, int otherRows) {
        public int total() {
            return receipts + shoppingLists + otherRows;
        }
    }

    // ================= copy =================

    /**
     * Copy {@code source}'s data into {@code destination} as new rows. {@code since}
     * null = everything; otherwise only rows created at/after it. Rows whose unique key
     * already exists in the destination are skipped (destination wins).
     */
    public CopyResult copyInto(Household source, Household destination, LocalDateTime since) {
        var customCategories = new CustomCategoryCopier(source, destination);
        customCategories.copyCreatedSince(since);

        var receipts = copyReceipts(source, destination, since);
        var shoppingLists = copyShoppingLists(source, destination, since);
        var keyedRows = copyKeyed(manualPurchaseRepository.findAllByHouseholdId(source.getId()),
                        manualPurchaseRepository.findAllByHouseholdId(destination.getId()), since,
                        purchase -> ManualPurchase.builder()
                                .household(destination).originHousehold(destination)
                                .user(purchase.getUser()).product(purchase.getProduct())
                                .quantity(purchase.getQuantity()).purchasedAt(purchase.getPurchasedAt())
                                .build(),
                        manualPurchaseRepository::saveAll)
                + copyKeyed(consumptionSnoozeRepository.findAllByHouseholdId(source.getId()),
                        consumptionSnoozeRepository.findAllByHouseholdId(destination.getId()), since,
                        snooze -> ConsumptionSnooze.builder()
                                .household(destination).originHousehold(destination)
                                .product(snooze.getProduct()).snoozedUntil(snooze.getSnoozedUntil())
                                .build(),
                        consumptionSnoozeRepository::saveAll)
                + copyKeyed(productAliasRepository.findAllByHouseholdId(source.getId()),
                        productAliasRepository.findAllByHouseholdId(destination.getId()), since,
                        alias -> HouseholdProductAlias.builder()
                                .household(destination).originHousehold(destination)
                                .product(alias.getProduct()).friendlyName(alias.getFriendlyName())
                                .build(),
                        productAliasRepository::saveAll)
                + copyKeyed(marketAliasRepository.findAllByHouseholdId(source.getId()),
                        marketAliasRepository.findAllByHouseholdId(destination.getId()), since,
                        alias -> HouseholdMarketAlias.builder()
                                .household(destination).originHousehold(destination)
                                .marketCnpj(alias.getMarketCnpj()).customName(alias.getCustomName())
                                .build(),
                        marketAliasRepository::saveAll)
                + copyKeyed(brandPreferenceRepository.findAllByHouseholdId(source.getId()),
                        brandPreferenceRepository.findAllByHouseholdId(destination.getId()), since,
                        preference -> ManualBrandPreference.builder()
                                .household(destination).originHousehold(destination)
                                .genericName(preference.getGenericName()).brand(preference.getBrand())
                                .strength(preference.getStrength())
                                .build(),
                        brandPreferenceRepository::saveAll)
                + copyKeyed(categoryOverrideRepository.findAllByHouseholdId(source.getId()),
                        categoryOverrideRepository.findAllByHouseholdId(destination.getId()), since,
                        override -> HouseholdProductCategoryOverride.builder()
                                .household(destination).originHousehold(destination)
                                .product(override.getProduct()).category(override.getCategory())
                                .customCategory(customCategories.resolve(override.getCustomCategory()))
                                .categoryNodeId(override.getCategoryNodeId())
                                .build(),
                        categoryOverrideRepository::saveAll);
        var otherRows = keyedRows + customCategories.copiedCount();

        var result = new CopyResult(receipts, shoppingLists, otherRows);
        log.info("household.copy source={} destination={} since={} receipts={} lists={} other={}",
                source.getId(), destination.getId(), since, receipts, shoppingLists, otherRows);
        return result;
    }

    private int copyReceipts(Household source, Household destination, LocalDateTime since) {
        var destinationKeys = keysOf(receiptRepository.findAllByHouseholdId(destination.getId()));
        var copies = receiptRepository.findAllByHouseholdId(source.getId()).stream()
                .filter(receipt -> COPYABLE_RECEIPT_STATUSES.contains(receipt.getStatus()))
                .filter(receipt -> createdSince(receipt, since))
                .filter(receipt -> destinationKeys.add(receipt.collisionKey()))
                .map(receipt -> copyReceipt(receipt, destination))
                .toList();
        receiptRepository.saveAll(copies);
        return copies.size();
    }

    private Receipt copyReceipt(Receipt original, Household destination) {
        var copy = Receipt.builder()
                .user(original.getUser())
                .household(destination)
                .originHousehold(destination)
                .chaveAcesso(original.getChaveAcesso())
                .origin(original.getOrigin())
                .entryMethod(original.getEntryMethod())
                .channel(original.getChannel())
                .uf(original.getUf())
                .cnpjEmitente(original.getCnpjEmitente())
                .marketName(original.getMarketName())
                .marketAddress(original.getMarketAddress())
                .issuedAt(original.getIssuedAt())
                .totalAmount(original.getTotalAmount())
                .discountTotal(original.getDiscountTotal())
                .approxTaxFederal(original.getApproxTaxFederal())
                .approxTaxEstadual(original.getApproxTaxEstadual())
                .qrPayload(original.getQrPayload())
                .sourceUrl(original.getSourceUrl())
                .rawHtml(original.getRawHtml())
                .status(original.getStatus())
                .confirmedAt(original.getConfirmedAt())
                .indexEligible(false)
                .build();
        original.getItems().forEach(item -> copy.addItem(copyReceiptItem(item)));
        return copy;
    }

    private ReceiptItem copyReceiptItem(ReceiptItem original) {
        return ReceiptItem.builder()
                .product(original.getProduct())
                .lineNumber(original.getLineNumber())
                .rawDescription(original.getRawDescription())
                .friendlyDescription(original.getFriendlyDescription())
                .ean(original.getEan())
                .quantity(original.getQuantity())
                .unit(original.getUnit())
                .unitPrice(original.getUnitPrice())
                .totalPrice(original.getTotalPrice())
                .excluded(original.isExcluded())
                .excludedFromPersonal(original.isExcludedFromPersonal())
                .nfcePromoFlag(original.isNfcePromoFlag())
                .paidUnitPrice(original.getPaidUnitPrice())
                .paidTotalPrice(original.getPaidTotalPrice())
                .categoryAtConfirmation(original.getCategoryAtConfirmation())
                .categoryNodeAtConfirmation(original.getCategoryNodeAtConfirmation())
                .aiCategoryId(original.getAiCategoryId())
                .build();
    }

    private int copyShoppingLists(Household source, Household destination, LocalDateTime since) {
        var copies = shoppingListRepository.findAllByHouseholdId(source.getId()).stream()
                .filter(list -> createdSince(list, since))
                .map(list -> copyShoppingList(list, destination))
                .toList();
        shoppingListRepository.saveAll(copies);
        return copies.size();
    }

    private ShoppingList copyShoppingList(ShoppingList original, Household destination) {
        var copy = ShoppingList.builder()
                .household(destination)
                .originHousehold(destination)
                .createdBy(original.getCreatedBy())
                .name(original.getName())
                .build();
        original.getItems().forEach(item -> copy.addItem(ShoppingListItem.builder()
                .product(item.getProduct())
                .freeText(item.getFreeText())
                .quantity(item.getQuantity())
                .position(item.getPosition())
                .checked(item.isChecked())
                .checkedAt(item.getCheckedAt())
                .build()));
        return copy;
    }

    // Copies rows whose unique key isn't in the destination yet (destination wins).
    private <T extends BaseEntity & HouseholdScoped> int copyKeyed(List<T> sourceRows, List<T> destinationRows,
                                                                    LocalDateTime since, UnaryOperator<T> cloner,
                                                                    Function<List<T>, ?> saveAll) {
        var destinationKeys = keysOf(destinationRows);
        var copies = sourceRows.stream()
                .filter(row -> createdSince(row, since))
                .filter(row -> row.collisionKey() == null || destinationKeys.add(row.collisionKey()))
                .map(cloner)
                .toList();
        saveAll.apply(copies);
        return copies.size();
    }

    /**
     * Copies custom categories on demand, keeping the parent chain and mapping each
     * source category to its destination counterpart: a same-name category already in
     * the destination is reused, otherwise a copy is created (parents first). An
     * override created after the join may point at a category created before it, so
     * {@link #resolve} copies referenced categories regardless of {@code since}.
     */
    private final class CustomCategoryCopier {
        private final Household source;
        private final Household destination;
        private final Map<UUID, HouseholdCustomCategory> resolved = new HashMap<>();
        private final Map<String, HouseholdCustomCategory> destinationByName = new HashMap<>();
        private int copied;

        CustomCategoryCopier(Household source, Household destination) {
            this.source = source;
            this.destination = destination;
            customCategoryRepository.findAllByHouseholdId(destination.getId())
                    .forEach(category -> destinationByName.put(category.getName(), category));
        }

        void copyCreatedSince(LocalDateTime since) {
            customCategoryRepository.findAllByHouseholdId(source.getId()).stream()
                    .filter(category -> createdSince(category, since))
                    .forEach(this::resolve);
        }

        HouseholdCustomCategory resolve(HouseholdCustomCategory original) {
            if (original == null) {
                return null;
            }
            var known = resolved.get(original.getId());
            if (known != null) {
                return known;
            }
            var counterpart = destinationByName.get(original.getName());
            if (counterpart == null) {
                counterpart = customCategoryRepository.save(HouseholdCustomCategory.builder()
                        .household(destination)
                        .originHousehold(destination)
                        .name(original.getName())
                        .icon(original.getIcon())
                        .color(original.getColor())
                        .parentCategory(original.getParentCategory())
                        .parentCustomCategory(resolve(original.getParentCustomCategory()))
                        .build());
                destinationByName.put(counterpart.getName(), counterpart);
                copied++;
            }
            resolved.put(original.getId(), counterpart);
            return counterpart;
        }

        int copiedCount() {
            return copied;
        }
    }

    // ================= absorb =================

    /**
     * Move everything in {@code source} into {@code home} and delete {@code source}.
     * Used when the last member leaves. Home wins collisions; the duplicate is deleted.
     * Returns how many rows moved.
     */
    public int absorbInto(Household source, Household home) {
        var moved = absorbCustomCategories(source, home);

        moved += absorbRows(receiptRepository.findAllByHouseholdId(source.getId()),
                receiptRepository.findAllByHouseholdId(home.getId()), home,
                (duplicate, survivor) -> priceObservationAuditRepository.reassignReceipt(
                        duplicate.getId(), survivor.getId(), home.getId()),
                receiptRepository::saveAll, receiptRepository::deleteAll);
        moved += absorbRows(shoppingListRepository.findAllByHouseholdId(source.getId()), List.of(), home,
                ignoreDuplicate(), shoppingListRepository::saveAll, shoppingListRepository::deleteAll);
        moved += absorbRows(manualPurchaseRepository.findAllByHouseholdId(source.getId()),
                manualPurchaseRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), manualPurchaseRepository::saveAll, manualPurchaseRepository::deleteAll);
        moved += absorbRows(consumptionSnoozeRepository.findAllByHouseholdId(source.getId()),
                consumptionSnoozeRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), consumptionSnoozeRepository::saveAll, consumptionSnoozeRepository::deleteAll);
        moved += absorbRows(productAliasRepository.findAllByHouseholdId(source.getId()),
                productAliasRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), productAliasRepository::saveAll, productAliasRepository::deleteAll);
        moved += absorbRows(marketAliasRepository.findAllByHouseholdId(source.getId()),
                marketAliasRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), marketAliasRepository::saveAll, marketAliasRepository::deleteAll);
        moved += absorbRows(brandPreferenceRepository.findAllByHouseholdId(source.getId()),
                brandPreferenceRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), brandPreferenceRepository::saveAll, brandPreferenceRepository::deleteAll);
        moved += absorbRows(categoryOverrideRepository.findAllByHouseholdId(source.getId()),
                categoryOverrideRepository.findAllByHouseholdId(home.getId()), home,
                ignoreDuplicate(), categoryOverrideRepository::saveAll, categoryOverrideRepository::deleteAll);

        var audits = priceObservationAuditRepository.reassignHousehold(source.getId(), home.getId());
        var scans = productScanRepository.reassignHousehold(source.getId(), home.getId());
        householdRepository.delete(source);
        log.info("household.absorbed source={} home={} moved={} audits={} scans={}",
                source.getId(), home.getId(), moved, audits, scans);
        return moved;
    }

    // Custom categories go first and need care: a moved child or a source override may
    // point at a duplicate that is about to be deleted, so every reference is re-pointed
    // to the home's same-name category BEFORE the duplicates are removed.
    private int absorbCustomCategories(Household source, Household home) {
        var sourceCategories = customCategoryRepository.findAllByHouseholdId(source.getId());
        var remap = new HashMap<UUID, HouseholdCustomCategory>();
        var duplicates = moveRows(sourceCategories, customCategoryRepository.findAllByHouseholdId(home.getId()), home,
                (duplicate, survivor) -> remap.put(duplicate.getId(), survivor), customCategoryRepository::saveAll);
        if (!remap.isEmpty()) {
            sourceCategories.stream()
                    .filter(category -> category.getParentCustomCategory() != null
                            && remap.containsKey(category.getParentCustomCategory().getId()))
                    .forEach(category -> category.setParentCustomCategory(remap.get(category.getParentCustomCategory().getId())));
            categoryOverrideRepository.findAllByHouseholdId(source.getId()).stream()
                    .filter(override -> override.getCustomCategory() != null
                            && remap.containsKey(override.getCustomCategory().getId()))
                    .forEach(override -> override.setCustomCategory(remap.get(override.getCustomCategory().getId())));
        }
        customCategoryRepository.deleteAll(duplicates);
        return sourceCategories.size() - duplicates.size();
    }

    private <T extends HouseholdScoped> int absorbRows(List<T> sourceRows, List<T> homeRows, Household home,
                                                       BiConsumer<T, T> onDuplicate,
                                                       Function<List<T>, ?> saveAll,
                                                       Consumer<List<T>> deleteAll) {
        var duplicates = moveRows(sourceRows, homeRows, home, onDuplicate, saveAll);
        deleteAll.accept(duplicates);
        return sourceRows.size() - duplicates.size();
    }

    // Moves every non-colliding row to home (it now belongs there for good, so its
    // origin moves too); colliding rows are handed to onDuplicate and returned.
    private <T extends HouseholdScoped> List<T> moveRows(List<T> sourceRows, List<T> homeRows, Household home,
                                                         BiConsumer<T, T> onDuplicate,
                                                         Function<List<T>, ?> saveAll) {
        var homeByKey = new HashMap<String, T>();
        homeRows.forEach(row -> {
            if (row.collisionKey() != null) {
                homeByKey.put(row.collisionKey(), row);
            }
        });
        var toMove = new ArrayList<T>();
        var duplicates = new ArrayList<T>();
        for (var row : sourceRows) {
            var survivor = row.collisionKey() == null ? null : homeByKey.get(row.collisionKey());
            if (survivor != null) {
                onDuplicate.accept(row, survivor);
                duplicates.add(row);
                continue;
            }
            if (row.collisionKey() != null) {
                homeByKey.put(row.collisionKey(), row);
            }
            row.setHousehold(home);
            row.setOriginHousehold(home);
            toMove.add(row);
        }
        saveAll.apply(toMove);
        return duplicates;
    }

    // ================= helpers =================

    private static <T> BiConsumer<T, T> ignoreDuplicate() {
        return (duplicate, survivor) -> { };
    }

    private static boolean createdSince(BaseEntity row, LocalDateTime since) {
        return since == null || row.getCreatedAt() == null || !row.getCreatedAt().isBefore(since);
    }

    private static <T extends HouseholdScoped> Set<String> keysOf(List<T> rows) {
        var keys = new HashSet<String>();
        rows.forEach(row -> {
            if (row.collisionKey() != null) {
                keys.add(row.collisionKey());
            }
        });
        return keys;
    }
}
