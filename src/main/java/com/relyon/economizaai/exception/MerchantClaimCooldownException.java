package com.relyon.economizaai.exception;

/** A new claim for the same chain is refused for a while after one was closed (429). */
public class MerchantClaimCooldownException extends DomainException {

    public MerchantClaimCooldownException(long hoursLeft) {
        super("merchant.claim.cooldown", String.valueOf(hoursLeft));
    }
}
