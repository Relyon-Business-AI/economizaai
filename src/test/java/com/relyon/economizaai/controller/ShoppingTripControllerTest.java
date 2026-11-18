package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.RecordShoppingTripRequest;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.shopping.ShoppingTripRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShoppingTripController.class)
@Import(SecurityConfig.class)
class ShoppingTripControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ShoppingTripRecorder shoppingTripRecorder;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private String tripJson(String stops) {
        return """
                {
                  "clientTripId": "trip-from-device-1",
                  "startedAt": "2026-11-18T10:00:00-03:00",
                  "finishedAt": "2026-11-18T11:10:00-03:00",
                  "outcome": "ENDED_EARLY",
                  "maxDistanceKm": 10, "maxStops": 2, "minSavingPerStop": 10,
                  "plannedTotal": 115.42, "plannedStopCount": 2, "unpricedItemCount": 0,
                  "skippedSavingTotal": 1.20, "priceLookupCount": 1,
                  "stops": %s
                }
                """.formatted(stops);
    }

    @Test
    void record_returns201WithTheTripId() throws Exception {
        var tripId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        when(shoppingTripRecorder.record(any(User.class), any(RecordShoppingTripRequest.class))).thenReturn(tripId);
        var stops = """
                [{
                  "marketCnpj": "11111111000111", "marketName": "Mercado do Bairro", "distanceKm": 0.4,
                  "concluded": true, "concludedAt": "2026-11-18T10:40:00-03:00",
                  "items": [{ "productId": "%s", "quantity": 6, "plannedUnitPrice": 5.29,
                              "priceSource": "LOCAL_HISTORY", "status": "NOT_FOUND",
                              "plannedStopPosition": 0 }]
                }]
                """.formatted(productId);

        mockMvc.perform(post("/api/v1/shopping-trips")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson(stops)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tripId.toString()));

        var captor = ArgumentCaptor.forClass(RecordShoppingTripRequest.class);
        verify(shoppingTripRecorder).record(any(User.class), captor.capture());
        var item = captor.getValue().stops().get(0).items().get(0);
        assertEquals(productId, item.productId());
        assertEquals(ShoppingTripItemStatus.NOT_FOUND, item.status());
    }

    @Test
    void record_withoutStops_returns400AndDoesNotCallService() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");

        mockMvc.perform(post("/api/v1/shopping-trips")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson("[]")))
                .andExpect(status().isBadRequest());

        verify(shoppingTripRecorder, never()).record(any(), any());
    }

    @Test
    void record_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/shopping-trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson("[]")))
                .andExpect(status().is4xxClientError());

        verify(shoppingTripRecorder, never()).record(any(), any());
    }
}
