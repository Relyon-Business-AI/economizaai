package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizaai.dto.request.CreateShoppingListRequest;
import com.relyon.economizaai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizaai.exception.InvalidShoppingListItemException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.exception.ShoppingListNotFoundException;
import com.relyon.economizaai.model.Household;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShoppingListServiceTest {

    @Mock private ShoppingListRepository listRepository;
    @Mock private ShoppingListItemRepository itemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;

    private ShoppingListService service;
    private User user;
    private Household household;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(listRepository, itemRepository, productRepository,
                householdProductAliasRepository, receiptItemRepository);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("owner@economizai").household(household).build();
        // No test asserts on friendly-name precedence here (covered separately); default to
        // "no explicit aliases" and "no receipt-derived friendly names".
        lenient().when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(any(), any()))
                .thenReturn(List.of());
        lenient().when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void listForHousehold_mapsAllListsToResponses() {
        var listOne = list("Semana", List.of());
        var listTwo = list("Festa", List.of());
        when(listRepository.findAllByHouseholdIdOrderByCreatedAtDesc(household.getId()))
                .thenReturn(List.of(listOne, listTwo));

        var responses = service.listForHousehold(user);

        assertEquals(2, responses.size());
        assertEquals("Semana", responses.get(0).name());
        assertEquals("Festa", responses.get(1).name());
    }

    @Test
    void getSole_returnsSingleList() {
        var list = list("Semana", List.of());
        when(listRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(list));

        var response = service.getSole(user);

        assertEquals(list.getId(), response.id());
        assertEquals("Semana", response.name());
    }

    @Test
    void getSole_throwsWhenNoLists() {
        when(listRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of());

        assertThrows(ShoppingListNotFoundException.class, () -> service.getSole(user));
    }

    @Test
    void getSole_throwsWhenMultipleLists() {
        var listOne = list("Semana", List.of());
        var listTwo = list("Festa", List.of());
        when(listRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(listOne, listTwo));

        assertThrows(ShoppingListNotFoundException.class, () -> service.getSole(user));
    }

    @Test
    void get_returnsOwnedList() {
        var list = list("Semana", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.get(user, list.getId());

        assertEquals(list.getId(), response.id());
        assertEquals("Semana", response.name());
    }

    @Test
    void get_prefersHouseholdFriendlyNameOverRawProductName() {
        var product = product("BATATA PALHA D.NONNA 440G");
        var list = list("Semana", List.of(itemWithProduct(product, 0)));
        var alias = HouseholdProductAlias.builder().household(household).product(product)
                .friendlyName("Batata Palha").build();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(household.getId(), List.of(product.getId())))
                .thenReturn(List.of(alias));

        var response = service.get(user, list.getId());

        var responseItem = response.items().get(0);
        assertEquals("BATATA PALHA D.NONNA 440G", responseItem.productName());
        assertEquals("Batata Palha", responseItem.friendlyDescription());
        assertEquals("Batata Palha", responseItem.displayName());
    }

    @Test
    void get_fallsBackToProductNameWhenNoFriendlyAlias() {
        var product = product("BATATA PALHA D.NONNA 440G");
        var list = list("Semana", List.of(itemWithProduct(product, 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.get(user, list.getId());

        var responseItem = response.items().get(0);
        assertNull(responseItem.friendlyDescription());
        assertEquals("BATATA PALHA D.NONNA 440G", responseItem.displayName());
    }

    @Test
    void get_fallsBackToReceiptFriendlyNameForDisplayWhenNoAlias() {
        var product = product("BATATA PALHA D.NONNA 440G");
        var list = list("Semana", List.of(itemWithProduct(product, 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        // No explicit alias, but the household named this product on a past receipt.
        when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(
                List.of(product.getId()), household.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{product.getId(), "Batata Palha"}));

        var response = service.get(user, list.getId());

        var responseItem = response.items().get(0);
        // friendlyDescription stays alias-only (null here); the receipt name only feeds displayName.
        assertNull(responseItem.friendlyDescription());
        assertEquals("BATATA PALHA D.NONNA 440G", responseItem.productName());
        assertEquals("Batata Palha", responseItem.displayName());
    }

    @Test
    void get_prefersExplicitAliasOverReceiptFriendlyName() {
        var product = product("BATATA PALHA D.NONNA 440G");
        var list = list("Semana", List.of(itemWithProduct(product, 0)));
        var alias = HouseholdProductAlias.builder().household(household).product(product)
                .friendlyName("Minha Batata").build();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(household.getId(), List.of(product.getId())))
                .thenReturn(List.of(alias));
        lenient().when(receiptItemRepository.findLatestFriendlyDescriptionsForHousehold(
                List.of(product.getId()), household.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{product.getId(), "Batata Palha"}));

        var response = service.get(user, list.getId());

        var responseItem = response.items().get(0);
        assertEquals("Minha Batata", responseItem.friendlyDescription());
        assertEquals("Minha Batata", responseItem.displayName());
    }

    @Test
    void get_throwsWhenListMissing() {
        var listId = UUID.randomUUID();
        when(listRepository.findById(listId)).thenReturn(Optional.empty());

        assertThrows(ShoppingListNotFoundException.class, () -> service.get(user, listId));
    }

    @Test
    void get_throwsWhenListBelongsToAnotherHousehold() {
        var otherHousehold = Household.builder().id(UUID.randomUUID()).inviteCode("XYZ999").build();
        var foreignList = ShoppingList.builder()
                .id(UUID.randomUUID())
                .household(otherHousehold)
                .createdBy(user)
                .name("Alheia")
                .build();
        var foreignListId = foreignList.getId();
        when(listRepository.findById(foreignListId)).thenReturn(Optional.of(foreignList));

        assertThrows(ShoppingListNotFoundException.class, () -> service.get(user, foreignListId));
    }

    @Test
    void create_withoutItems_savesAndReturnsEmptyList() {
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var request = new CreateShoppingListRequest("Mercado", null);
        var response = service.create(user, request);

        assertEquals("Mercado", response.name());
        assertEquals(0, response.totalItems());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void create_withItems_persistsEachItemWithIncreasingPositions() {
        var product = product("Cafe");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var items = List.of(
                new CreateShoppingListRequest.Item(product.getId(), null, new BigDecimal("2")),
                new CreateShoppingListRequest.Item(null, "papel higienico", null)
        );
        var response = service.create(user, new CreateShoppingListRequest("Compras", items));

        assertEquals(2, response.totalItems());
        verify(itemRepository, times(2)).save(any());
    }

    @Test
    void rename_updatesNameAndSaves() {
        var list = list("Antigo", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(listRepository.save(list)).thenReturn(list);

        var response = service.rename(user, list.getId(), new UpdateShoppingListRequest("Novo"));

        assertEquals("Novo", response.name());
        verify(listRepository).save(list);
    }

    @Test
    void delete_removesOwnedList() {
        var list = list("Descartavel", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.delete(user, list.getId());

        verify(listRepository).delete(list);
    }

    @Test
    void addItem_appendsAtNextPosition() {
        var existing = item("pao", 0);
        var list = list("Compras", List.of(existing));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var request = new AddShoppingListItemRequest(null, "leite", new BigDecimal("3"));
        var response = service.addItem(user, list.getId(), request);

        assertEquals(2, response.totalItems());
        verify(itemRepository).save(any());
        // newly appended item should be at position 1 (max existing 0 + 1)
        var appended = list.getItems().get(1);
        assertEquals(1, appended.getPosition());
        assertEquals("leite", appended.getFreeText());
    }

    @Test
    void addItem_onEmptyListUsesPositionZero() {
        var list = list("Vazia", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.addItem(user, list.getId(), new AddShoppingListItemRequest(null, "ovos", null));

        assertEquals(0, list.getItems().get(0).getPosition());
        // default quantity is ONE when not provided
        assertEquals(0, list.getItems().get(0).getQuantity().compareTo(BigDecimal.ONE));
    }

    @Test
    void addItem_throwsWhenNeitherProductNorFreeText() {
        var list = list("Compras", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var request = new AddShoppingListItemRequest(null, "   ", BigDecimal.ONE);
        var listId = list.getId();
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.addItem(user, listId, request));
    }

    @Test
    void addItem_throwsWhenBothProductAndFreeText() {
        var list = list("Compras", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Exactly-one rule: providing BOTH productId and freeText is invalid.
        var request = new AddShoppingListItemRequest(UUID.randomUUID(), "leite", BigDecimal.ONE);
        var listId = list.getId();
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.addItem(user, listId, request));
        verify(itemRepository, never()).save(any());
        verify(productRepository, never()).findById(any());
    }

    @Test
    void create_throwsWhenItemHasBothProductAndFreeText() {
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var items = List.of(new CreateShoppingListRequest.Item(UUID.randomUUID(), "papel", BigDecimal.ONE));
        var createRequest = new CreateShoppingListRequest("Compras", items);
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.create(user, createRequest));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void addItem_throwsWhenProductMissing() {
        var list = list("Compras", List.of());
        var missingProductId = UUID.randomUUID();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(productRepository.findById(missingProductId)).thenReturn(Optional.empty());

        var request = new AddShoppingListItemRequest(missingProductId, null, BigDecimal.ONE);
        var listId = list.getId();
        assertThrows(ProductNotFoundException.class,
                () -> service.addItem(user, listId, request));
    }

    @Test
    void toggleItem_checksUncheckedItemAndStampsTime() {
        var target = item("arroz", 0);
        target.setChecked(false);
        var list = list("Compras", List.of(target));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.toggleItem(user, list.getId(), target.getId());

        assertTrue(target.isChecked());
        assertNotNull(target.getCheckedAt());
        assertEquals(1, response.checkedItems());
    }

    @Test
    void toggleItem_unchecksCheckedItemAndClearsTime() {
        var target = item("arroz", 0);
        target.setChecked(true);
        target.setCheckedAt(LocalDateTime.now());
        var list = list("Compras", List.of(target));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.toggleItem(user, list.getId(), target.getId());

        assertFalse(target.isChecked());
        assertNull(target.getCheckedAt());
    }

    @Test
    void toggleItem_throwsWhenItemNotOnList() {
        var list = list("Compras", List.of(item("arroz", 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        var listId = list.getId();
        var missingItemId = UUID.randomUUID();

        assertThrows(ShoppingListNotFoundException.class,
                () -> service.toggleItem(user, listId, missingItemId));
    }

    @Test
    void removeItem_deletesAndDetachesFromList() {
        var keep = item("arroz", 0);
        var drop = item("feijao", 1);
        var list = list("Compras", List.of(keep, drop));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.removeItem(user, list.getId(), drop.getId());

        assertEquals(1, response.totalItems());
        assertFalse(list.getItems().contains(drop));
        verify(itemRepository).delete(drop);
    }

    @Test
    void removeItem_throwsWhenItemNotOnList() {
        var list = list("Compras", List.of(item("arroz", 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        var listId = list.getId();
        var missingItemId = UUID.randomUUID();

        assertThrows(ShoppingListNotFoundException.class,
                () -> service.removeItem(user, listId, missingItemId));
    }

    private ShoppingList list(String name, List<ShoppingListItem> items) {
        var list = ShoppingList.builder()
                .id(UUID.randomUUID())
                .household(household)
                .createdBy(user)
                .name(name)
                .build();
        items.forEach(list::addItem);
        return list;
    }

    private ShoppingListItem item(String freeText, int position) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .freeText(freeText)
                .quantity(BigDecimal.ONE)
                .position(position)
                .build();
    }

    private ShoppingListItem itemWithProduct(Product product, int position) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .product(product)
                .quantity(BigDecimal.ONE)
                .position(position)
                .build();
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }
}
