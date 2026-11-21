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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    private static final String ONE_STOP = """
            [{
              "marketCnpj": "11111111000111", "distanceKm": 0.4, "concluded": true,
              "items": [{ "productId": "8a1f5c0e-3a0b-4d57-9b0c-0c6a2f3f6b11", "quantity": 6,
                          "plannedUnitPrice": 5.29, "status": "IN_CART" }]
            }]
            """;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "\"maxDistanceKm\": 10      | \"maxDistanceKm\": 100000",
            "\"minSavingPerStop\": 10   | \"minSavingPerStop\": 10000000000",
            "\"plannedTotal\": 115.42   | \"plannedTotal\": 10000000000",
            "\"skippedSavingTotal\": 1.20 | \"skippedSavingTotal\": 10000000000",
            "\"distanceKm\": 0.4        | \"distanceKm\": 100000",
            "\"concluded\": true        | \"concluded\": true, \"receiptPaidTotal\": 10000000000",
            "\"concluded\": true        | \"concluded\": true, \"receiptPredictedTotal\": -1",
            "\"quantity\": 6            | \"quantity\": 1000000000",
            "\"plannedUnitPrice\": 5.29 | \"plannedUnitPrice\": 10000000000",
            "\"status\": \"IN_CART\"    | \"status\": \"IN_CART\", \"paidUnitPrice\": 10000000000",
    })
    void record_withANumberTheTablesCannotHold_returns400AndDoesNotCallService(String valid, String tooLarge)
            throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");

        mockMvc.perform(post("/api/v1/shopping-trips")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson(ONE_STOP).replace(valid, tooLarge)))
                .andExpect(status().isBadRequest());

        verify(shoppingTripRecorder, never()).record(any(), any());
    }

    @Test
    void record_whenTheItemLinesAddUpBeyondAMoneyColumn_returns400() throws Exception {
        when(localizedMessageService.translate(anyString())).thenReturn("validation failed");
        var eachLineFitsButNotTheSum = tripJson(ONE_STOP)
                .replace("\"quantity\": 6", "\"quantity\": 999999999")
                .replace("\"plannedUnitPrice\": 5.29", "\"plannedUnitPrice\": 9999999999");

        mockMvc.perform(post("/api/v1/shopping-trips")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eachLineFitsButNotTheSum))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.itemsTotalStorable").exists());

        verify(shoppingTripRecorder, never()).record(any(), any());
    }

    @Test
    void record_atTheLargestValuesTheTablesHold_returns201() throws Exception {
        when(shoppingTripRecorder.record(any(User.class), any(RecordShoppingTripRequest.class)))
                .thenReturn(UUID.randomUUID());
        var atTheLimits = tripJson(ONE_STOP)
                .replace("\"maxDistanceKm\": 10", "\"maxDistanceKm\": 99999.9")
                .replace("\"plannedTotal\": 115.42", "\"plannedTotal\": 9999999999.99")
                .replace("\"distanceKm\": 0.4", "\"distanceKm\": 99999.999")
                .replace("\"quantity\": 6", "\"quantity\": 0.001")
                .replace("\"plannedUnitPrice\": 5.29", "\"plannedUnitPrice\": 9999999999.99");

        mockMvc.perform(post("/api/v1/shopping-trips")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(atTheLimits))
                .andExpect(status().isCreated());
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
