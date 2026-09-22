package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdProductAliasService {

    private final HouseholdProductAliasRepository repository;
    private final ReceiptItemRepository receiptItemRepository;

    /**
     * If the user named this item AND it's linked to a Product, remember
     * the name household-wide so future receipts of the same product
     * inherit it.
     */
    @Transactional
    public void rememberFromItem(Household household, ReceiptItem item) {
        if (item.getProduct() == null) return;
        if (item.getFriendlyDescription() == null || item.getFriendlyDescription().isBlank()) return;
        upsert(household, item.getProduct(), item.getFriendlyDescription());
    }

    @Transactional
    public void upsert(Household household, Product product, String friendlyName) {
        var existing = repository.findByHouseholdIdAndProductId(household.getId(), product.getId());
        var alias = existing.orElseGet(() -> HouseholdProductAlias.builder()
                .household(household).product(product).build());
        alias.setFriendlyName(friendlyName);
        repository.save(alias);
        log.info("household_product_alias.upsert household={} product={} name='{}'",
                household.getId(), product.getId(), friendlyName);
    }

    /**
     * Pulls the household's existing friendly name for this product, if any.
     * Used by canonicalization to seed friendlyDescription on newly-linked items.
     */
    @Transactional(readOnly = true)
    public String findFor(Household household, Product product) {
        if (product == null) return null;
        return repository.findByHouseholdIdAndProductId(household.getId(), product.getId())
                .map(HouseholdProductAlias::getFriendlyName)
                .orElse(null);
    }

    /**
     * The household's renames for these products, keyed by product id. Batch
     * variant for list endpoints — one query instead of one per row.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> friendlyNamesFor(UUID householdId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return repository.findAllByHouseholdIdAndProductIdIn(householdId, List.copyOf(productIds)).stream()
                .collect(Collectors.toMap(alias -> alias.getProduct().getId(),
                        HouseholdProductAlias::getFriendlyName));
    }

    /**
     * Display-friendly name per product: the explicit alias when set, else the
     * household's most recent friendly name from a confirmed receipt for the
     * same product. The second tier catches items renamed via {@code
     * ReceiptService.addItem} (or before their product link existed) — those
     * never create an explicit alias, but should still surface consistently
     * with the shopping list / receipt screens. Null when neither exists.
     * Batch variant for list endpoints (product search, recently-viewed).
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> resolvedNamesFor(UUID householdId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        var ids = List.copyOf(productIds);
        var resolved = new HashMap<>(friendlyNamesFor(householdId, ids));
        for (var row : receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(ids, householdId)) {
            resolved.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        return resolved;
    }

    /** Single-product convenience wrapper around {@link #resolvedNamesFor}. */
    @Transactional(readOnly = true)
    public String resolvedNameFor(UUID householdId, UUID productId) {
        if (productId == null) return null;
        return resolvedNamesFor(householdId, List.of(productId)).get(productId);
    }
}
