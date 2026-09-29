package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.exception.BillingCheckoutException;
import com.relyon.economizaai.exception.BillingNotConfiguredException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.subscription.MercadoPagoClient.Preapproval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MercadoPagoCheckoutServiceTest {

    @Mock private MercadoPagoClient mercadoPagoClient;
    @Mock private MercadoPagoProperties properties;

    @InjectMocks private MercadoPagoCheckoutService service;

    private final User user = User.builder().id(UUID.randomUUID()).email("maria@example.com").build();

    @Test
    void createCheckoutReturnsInitPointUrl() {
        when(properties.isCheckoutConfigured()).thenReturn(true);
        when(mercadoPagoClient.createPreapproval("maria@example.com", user.getId().toString()))
                .thenReturn(new Preapproval("pre_1", "pending", "https://mp/init_point", null, null, null, null));

        var response = service.createCheckout(user);

        assertEquals("https://mp/init_point", response.checkoutUrl());
    }

    @Test
    void createCheckoutFailsClosedWhenNotConfigured() {
        when(properties.isCheckoutConfigured()).thenReturn(false);

        assertThrows(BillingNotConfiguredException.class, () -> service.createCheckout(user));
        verifyNoInteractions(mercadoPagoClient);
    }

    @Test
    void createCheckoutWrapsApiFailure() {
        when(properties.isCheckoutConfigured()).thenReturn(true);
        when(mercadoPagoClient.createPreapproval(anyString(), anyString()))
                .thenThrow(new MercadoPagoApiException("500"));

        assertThrows(BillingCheckoutException.class, () -> service.createCheckout(user));
    }
}
