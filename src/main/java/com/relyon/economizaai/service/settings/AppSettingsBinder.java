package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.config.AppConfigProperties;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.service.HouseholdService;
import com.relyon.economizaai.service.profile.StoreReviewPromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The apps (force update, store review, household invite) and merchant accounts. */
@Component
@RequiredArgsConstructor
public class AppSettingsBinder implements SettingBinder {

    private final AppConfigProperties appConfig;
    private final StoreReviewPromptService storeReview;
    private final HouseholdService households;
    private final CollaborativeProperties properties;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.text(RuntimeSetting.APP_MIN_VERSION, appConfig::getMinVersion, appConfig::setMinVersion);
        bindings.text(RuntimeSetting.APP_ANDROID_URL, appConfig::getAndroidUrl, appConfig::setAndroidUrl);
        bindings.text(RuntimeSetting.APP_IOS_URL, appConfig::getIosUrl, appConfig::setIosUrl);
        bindings.text(RuntimeSetting.APP_UPDATE_MESSAGE, appConfig::getMessage, appConfig::setMessage);

        bindings.integer(RuntimeSetting.STORE_REVIEW_MIN_RECEIPTS,
                storeReview::getMinConfirmedReceipts, storeReview::setMinConfirmedReceipts);
        bindings.integer(RuntimeSetting.STORE_REVIEW_FAILURE_LOOKBACK_DAYS,
                storeReview::getFailureLookbackDays, storeReview::setFailureLookbackDays);
        bindings.integer(RuntimeSetting.STORE_REVIEW_MAX_PROMPTS, storeReview::getMaxPrompts, storeReview::setMaxPrompts);
        bindings.integer(RuntimeSetting.STORE_REVIEW_COOLDOWN_DAYS, storeReview::getCooldownDays, storeReview::setCooldownDays);
        bindings.integer(RuntimeSetting.HOUSEHOLD_INVITE_HOURS, households::getInviteTtlHours, households::setInviteTtlHours);

        bindMerchant(bindings);
    }

    private void bindMerchant(SettingBindings bindings) {
        var merchant = properties.getMerchant();
        bindings.toggle(RuntimeSetting.MERCHANT_PROMOS_FEED_ENABLED, merchant::isPromosFeedEnabled, merchant::setPromosFeedEnabled);
        bindings.date(RuntimeSetting.MERCHANT_FREE_UNTIL, merchant::getFreeUntil, merchant::setFreeUntil);
        bindings.integer(RuntimeSetting.MERCHANT_CLAIM_CODE_TTL_HOURS,
                merchant::getClaimCodeTtlHours, merchant::setClaimCodeTtlHours);
        bindings.integer(RuntimeSetting.MERCHANT_CLAIM_CODE_MAX_ATTEMPTS,
                merchant::getClaimCodeMaxAttempts, merchant::setClaimCodeMaxAttempts);
        bindings.integer(RuntimeSetting.MERCHANT_CLAIM_COOLDOWN_HOURS,
                merchant::getClaimCooldownHours, merchant::setClaimCooldownHours);
        bindings.integer(RuntimeSetting.MERCHANT_IMPORT_MAX_ROWS, merchant::getImportMaxRows, merchant::setImportMaxRows);
    }
}
