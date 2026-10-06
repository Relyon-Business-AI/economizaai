package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantSubscription;
import com.relyon.economizaai.model.enums.MerchantSubscriptionStatus;
import com.relyon.economizaai.time.BrazilClock;

import java.time.LocalDate;

/**
 * Marketing-account standing of one chain. {@code active} is the gate the FE
 * should trust; {@code freeUntil} + status PROMO powers the launch-promo modal
 * ("conta de marketing grátis até {freeUntil}").
 */
public record MerchantSubscriptionResponse(
        String cnpjRoot,
        String plan,
        MerchantSubscriptionStatus status,
        LocalDate freeUntil,
        boolean active) {

    public static MerchantSubscriptionResponse from(MerchantSubscription subscription) {
        return new MerchantSubscriptionResponse(
                subscription.getCnpjRoot(),
                subscription.getPlan(),
                subscription.getStatus(),
                subscription.getFreeUntil(),
                subscription.isActiveOn(BrazilClock.today()));
    }
}
