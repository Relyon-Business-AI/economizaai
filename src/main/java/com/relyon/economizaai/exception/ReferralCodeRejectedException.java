package com.relyon.economizaai.exception;

import lombok.Getter;

/**
 * Raised when a referral code can't be redeemed. A bad code (unknown, or the user's own) maps to
 * 400; an account that can no longer be referred (already referred, or already has a confirmed
 * receipt) maps to 409.
 */
@Getter
public class ReferralCodeRejectedException extends DomainException {

    private final boolean accountNoLongerEligible;

    private ReferralCodeRejectedException(String messageKey, boolean accountNoLongerEligible) {
        super(messageKey);
        this.accountNoLongerEligible = accountNoLongerEligible;
    }

    public static ReferralCodeRejectedException invalidCode() {
        return new ReferralCodeRejectedException("referral.code.invalid", false);
    }

    public static ReferralCodeRejectedException ownCode() {
        return new ReferralCodeRejectedException("referral.code.own", false);
    }

    public static ReferralCodeRejectedException alreadyReferred() {
        return new ReferralCodeRejectedException("referral.redeem.already_referred", true);
    }

    public static ReferralCodeRejectedException tooLate() {
        return new ReferralCodeRejectedException("referral.redeem.too_late", true);
    }
}
