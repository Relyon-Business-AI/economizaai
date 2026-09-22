package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizaai.dto.request.CreateShoppingListRequest;
import com.relyon.economizaai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingListResponse;
import com.relyon.economizaai.exception.InvalidShoppingListItemException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.exception.ShoppingListNotFoundException;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ShoppingList;
import com.relyon.economizaai.model.ShoppingListItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ShoppingListItemRepository;
import com.relyon.economizaai.repository.ShoppingListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for persistent household shopping lists. The existing
 * {@link ShoppingListOptimizer} stays as the stateless one-shot helper
 * for ad-hoc "optimize this list right now" calls; this service backs
 * the FE workflow of build → edit → check off as you shop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private final ShoppingListRepository listRepository;
    private final ShoppingListItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final ReceiptItemRepository receiptItemRepository;

    @Transactional(readOnly = true)
    public List<ShoppingListResponse> listForHousehold(User user) {
        return listRepository.findAllByHouseholdIdOrderByCreatedAtDesc(user.getHousehold().getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Returns the household's single shopping list; throws {@link ShoppingListNotFoundException} when count ≠ 1. */
    @Transactional(readOnly = true)
    public ShoppingListResponse getSole(User user) {
        var lists = listRepository.findAllByHouseholdId(user.getHousehold().getId());
        if (lists.size() != 1) throw new ShoppingListNotFoundException();
        return toResponse(lists.get(0));
    }

    @Transactional(readOnly = true)
    public ShoppingListResponse get(User user, UUID listId) {
        return toResponse(loadOwned(user, listId));
    }

    @Transactional
    public ShoppingListResponse create(User user, CreateShoppingListRequest request) {
        var list = ShoppingList.builder()
                .household(user.getHousehold())
                .createdBy(user)
                .name(request.name())
                .build();
        var saved = listRepository.save(list);
        if (request.items() != null) {
            var pos = 0;
            for (var item : request.items()) {
                var entity = buildItem(item.productId(), item.freeText(), item.quantity(), pos++);
                saved.addItem(entity);
                itemRepository.save(entity);
            }
        }
        log.info("shopping_list.created household={} name='{}' items={}",
                user.getHousehold().getId(), request.name(), saved.getItems().size());
        return toResponse(saved);
    }

    @Transactional
    public ShoppingListResponse rename(User user, UUID listId, UpdateShoppingListRequest request) {
        var list = loadOwned(user, listId);
        list.setName(request.name());
        return toResponse(listRepository.save(list));
    }

    @Transactional
    public void delete(User user, UUID listId) {
        var list = loadOwned(user, listId);
        listRepository.delete(list);
        log.info("shopping_list.deleted id={}", listId);
    }

    @Transactional
    public ShoppingListResponse addItem(User user, UUID listId, AddShoppingListItemRequest request) {
        var list = loadOwned(user, listId);
        var nextPos = list.getItems().stream().mapToInt(ShoppingListItem::getPosition).max().orElse(-1) + 1;
        var item = buildItem(request.productId(), request.freeText(), request.quantity(), nextPos);
        list.addItem(item);
        itemRepository.save(item);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse toggleItem(User user, UUID listId, UUID itemId) {
        var list = loadOwned(user, listId);
        var item = list.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ShoppingListNotFoundException::new);
        item.setChecked(!item.isChecked());
        item.setCheckedAt(item.isChecked() ? LocalDateTime.now() : null);
        itemRepository.save(item);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse removeItem(User user, UUID listId, UUID itemId) {
        var list = loadOwned(user, listId);
        var item = list.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ShoppingListNotFoundException::new);
        list.getItems().remove(item);
        itemRepository.delete(item);
        return toResponse(list);
    }

    private ShoppingListResponse toResponse(ShoppingList list) {
        var productIds = list.getItems().stream()
                .map(ShoppingListItem::getProduct)
                .filter(Objects::nonNull)
                .map(Product::getId)
                .distinct()
                .toList();
        return ShoppingListResponse.from(list,
                aliasesByProduct(list, productIds),
                productFriendlyNamesByProduct(list, productIds));
    }

    /** The household's explicit renames (household_product_aliases) for these products, keyed by product id. */
    private Map<UUID, String> aliasesByProduct(ShoppingList list, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return householdProductAliasRepository
                .findAllByHouseholdIdAndProductIdIn(list.getHousehold().getId(), productIds)
                .stream()
                .collect(Collectors.toMap(alias -> alias.getProduct().getId(), HouseholdProductAlias::getFriendlyName));
    }

    /**
     * The household's own friendly name per product, taken from its most recent confirmed
     * receipt (user-typed or inherited) — the displayName fallback for products the household
     * hasn't explicitly renamed, so the list shows a human name instead of the raw SEFAZ
     * product name. Rows arrive newest-first, so the first hit per product wins.
     */
    private Map<UUID, String> productFriendlyNamesByProduct(ShoppingList list, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        var byProduct = new HashMap<UUID, String>();
        for (var row : receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(
                productIds, list.getHousehold().getId())) {
            byProduct.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        return byProduct;
    }

    private ShoppingList loadOwned(User user, UUID listId) {
        var list = listRepository.findById(listId).orElseThrow(ShoppingListNotFoundException::new);
        if (!list.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ShoppingListNotFoundException();
        }
        return list;
    }

    private ShoppingListItem buildItem(UUID productId, String freeText, BigDecimal quantity, int position) {
        var hasProduct = productId != null;
        var hasFreeText = freeText != null && !freeText.isBlank();
        if (hasProduct == hasFreeText) {
            // Exactly one of productId/freeText is required — reject neither and both.
            throw new InvalidShoppingListItemException();
        }
        var builder = ShoppingListItem.builder()
                .position(position)
                .quantity(quantity != null ? quantity : BigDecimal.ONE)
                .freeText(hasFreeText ? freeText : null);
        if (hasProduct) {
            builder.product(productRepository.findById(productId).orElseThrow(ProductNotFoundException::new));
        }
        return builder.build();
    }
}
