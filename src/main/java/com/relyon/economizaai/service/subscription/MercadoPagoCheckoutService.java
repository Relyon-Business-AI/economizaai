package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.dto.response.CheckoutResponse;
import com.relyon.economizaai.exception.BillingCheckoutException;
import com.relyon.economizaai.exception.BillingNotConfiguredException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates the Mercado Pago checkout for the web subscription. No DB writes
 * here — entitlement only flips when the webhook confirms the preapproval was
 * authorized, so an abandoned checkout leaves no trace. No transaction is held
 * across the outbound call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoCheckoutService {

    private final MercadoPagoClient mercadoPagoClient;
    private final MercadoPagoProperties properties;

    public CheckoutResponse createCheckout(User user) {
        if (!properties.isCheckoutConfigured()) {
            throw new BillingNotConfiguredException();
        }
        try {
            var preapproval = mercadoPagoClient.createPreapproval(user.getEmail(), user.getId().toString());
            log.info("billing.checkout.created user={} preapproval={}",
                    LogMasker.email(user.getEmail()), preapproval.id());
            return new CheckoutResponse(preapproval.initPoint());
        } catch (MercadoPagoApiException apiFailure) {
            log.error("billing.checkout.failed user={} reason={}",
                    LogMasker.email(user.getEmail()), apiFailure.getMessage());
            throw new BillingCheckoutException();
        }
    }
}
