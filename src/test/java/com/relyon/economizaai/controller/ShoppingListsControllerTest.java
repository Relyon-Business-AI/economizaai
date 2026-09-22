package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizaai.dto.request.CreateShoppingListRequest;
import com.relyon.economizaai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingListResponse;
import com.relyon.economizaai.exception.InvalidShoppingListItemException;
import com.relyon.economizaai.exception.ShoppingListNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.shopping.ShoppingListService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShoppingListsController.class)
@Import(SecurityConfig.class)
class ShoppingListsControllerTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ShoppingListService service;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    private ShoppingListResponse sampleList(UUID listId, String name) {
        var item = new ShoppingListResponse.Item(
                UUID.randomUUID(), null, null, null, "papel higienico", "papel higienico",
                BigDecimal.ONE, 0, false, null);
        return new ShoppingListResponse(listId, name, UUID.randomUUID(),
                LocalDateTime.now(), LocalDateTime.now(), 1, 0, List.of(item));
    }

    @Test
    void list_returnsHouseholdLists() throws Exception {
        when(service.listForHousehold(any(User.class)))
                .thenReturn(List.of(sampleList(UUID.randomUUID(), "Compra da semana")));

        mockMvc.perform(get("/api/v1/shopping-lists")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Compra da semana"))
                .andExpect(jsonPath("$[0].totalItems").value(1));
    }

    @Test
    void get_returnsList() throws Exception {
        var listId = UUID.randomUUID();
        when(service.get(any(User.class), eq(listId))).thenReturn(sampleList(listId, "Mensal"));

        mockMvc.perform(get("/api/v1/shopping-lists/{id}", listId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(listId.toString()))
                .andExpect(jsonPath("$.name").value("Mensal"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        var listId = UUID.randomUUID();
        when(service.get(any(User.class), eq(listId))).thenThrow(new ShoppingListNotFoundException());
        when(localizedMessageService.translate(any(ShoppingListNotFoundException.class)))
                .thenReturn("Shopping list not found");

        mockMvc.perform(get("/api/v1/shopping-lists/{id}", listId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void create_returnsCreated() throws Exception {
        var listId = UUID.randomUUID();
        when(service.create(any(User.class), any(CreateShoppingListRequest.class)))
                .thenReturn(sampleList(listId, "Compra da semana"));

        var body = objectMapper.writeValueAsString(
                new CreateShoppingListRequest("Compra da semana", null));
        mockMvc.perform(post("/api/v1/shopping-lists")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(listId.toString()))
                .andExpect(jsonPath("$.name").value("Compra da semana"));
    }

    @Test
    void create_blankName_returns400() throws Exception {
        var body = objectMapper.writeValueAsString(new CreateShoppingListRequest("", null));
        when(localizedMessageService.translate("validation.failed")).thenReturn("Validation failed");

        mockMvc.perform(post("/api/v1/shopping-lists")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rename_returnsUpdated() throws Exception {
        var listId = UUID.randomUUID();
        when(service.rename(any(User.class), eq(listId), any(UpdateShoppingListRequest.class)))
                .thenReturn(sampleList(listId, "Novo nome"));

        var body = objectMapper.writeValueAsString(new UpdateShoppingListRequest("Novo nome"));
        mockMvc.perform(patch("/api/v1/shopping-lists/{id}", listId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Novo nome"));
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        var listId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/shopping-lists/{id}", listId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNoContent());
    }

    @Test
    void addItem_returnsCreated() throws Exception {
        var listId = UUID.randomUUID();
        when(service.addItem(any(User.class), eq(listId), any(AddShoppingListItemRequest.class)))
                .thenReturn(sampleList(listId, "Compra"));

        var body = objectMapper.writeValueAsString(
                new AddShoppingListItemRequest(null, "azeite extra virgem", new BigDecimal("2")));
        mockMvc.perform(post("/api/v1/shopping-lists/{id}/items", listId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(listId.toString()));
    }

    @Test
    void addItem_invalidItem_returns400() throws Exception {
        var listId = UUID.randomUUID();
        when(service.addItem(any(User.class), eq(listId), any(AddShoppingListItemRequest.class)))
                .thenThrow(new InvalidShoppingListItemException());
        when(localizedMessageService.translate(any(InvalidShoppingListItemException.class)))
                .thenReturn("Invalid item");

        var body = objectMapper.writeValueAsString(
                new AddShoppingListItemRequest(null, null, null));
        mockMvc.perform(post("/api/v1/shopping-lists/{id}/items", listId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void toggleItem_returnsList() throws Exception {
        var listId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        when(service.toggleItem(any(User.class), eq(listId), eq(itemId)))
                .thenReturn(sampleList(listId, "Compra"));

        mockMvc.perform(post("/api/v1/shopping-lists/{id}/items/{itemId}/toggle", listId, itemId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(listId.toString()));
    }

    @Test
    void removeItem_returnsList() throws Exception {
        var listId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        when(service.removeItem(any(User.class), eq(listId), eq(itemId)))
                .thenReturn(sampleList(listId, "Compra"));

        mockMvc.perform(delete("/api/v1/shopping-lists/{id}/items/{itemId}", listId, itemId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(listId.toString()));
    }

    @Test
    void removeItem_itemNotFound_returns404() throws Exception {
        var listId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        doThrow(new ShoppingListNotFoundException())
                .when(service).removeItem(any(User.class), eq(listId), eq(itemId));
        when(localizedMessageService.translate(any(ShoppingListNotFoundException.class)))
                .thenReturn("Shopping list not found");

        mockMvc.perform(delete("/api/v1/shopping-lists/{id}/items/{itemId}", listId, itemId)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/shopping-lists"))
                .andExpect(status().isUnauthorized());
    }
}
