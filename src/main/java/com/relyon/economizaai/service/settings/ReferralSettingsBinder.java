package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.MonetizationProperties;
import com.relyon.economizaai.service.DataShareConsentService;
import com.relyon.economizaai.service.merchant.MerchantPromoService;
import com.relyon.economizaai.service.subscription.PremiumGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Referral rewards, the modeled subscriber lifetime, the data-copy consent and the merchant badge. */
@Component
@RequiredArgsConstructor
public class ReferralSettingsBinder implements SettingBinder {

    private final PremiumGrantService premiumGrants;
    private final MonetizationProperties monetization;
    private final DataShareConsentService dataShareConsent;
    private final MerchantPromoService merchantPromos;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.REFERRAL_REWARD_MONTHS,
                premiumGrants::getReferralRewardMonths, premiumGrants::setReferralRewardMonths);
        bindings.integer(RuntimeSetting.PREMIUM_ASSUMED_LIFETIME_MONTHS,
                monetization::getAssumedLifetimeMonths, monetization::setAssumedLifetimeMonths);
        bindings.integer(RuntimeSetting.DATA_SHARE_CONSENT_DAYS,
                dataShareConsent::getConsentDays, dataShareConsent::setConsentDays);
        bindings.decimal(RuntimeSetting.MERCHANT_VERIFIED_BADGE_TOLERANCE_PCT,
                merchantPromos::getVerifiedBadgeTolerancePct, merchantPromos::setVerifiedBadgeTolerancePct);
    }
}
