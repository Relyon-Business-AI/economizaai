package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.exception.InvalidSettingValueException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingValueCodecTest {

    @Test
    void integer_isTrimmedAndKeptInsideTheRange() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.PRICE_WINDOW_DAYS, " 90 ")).isEqualTo("90");
        assertRefused(RuntimeSetting.PRICE_WINDOW_DAYS, "6", "admin.settings.value.out-of-range");
        assertRefused(RuntimeSetting.PRICE_WINDOW_DAYS, "366", "admin.settings.value.out-of-range");
        assertRefused(RuntimeSetting.PRICE_WINDOW_DAYS, "90.5", "admin.settings.value.not-integer");
        assertRefused(RuntimeSetting.PRICE_WINDOW_DAYS, "", "admin.settings.value.not-integer");
    }

    @Test
    void decimal_acceptsCommaAsDecimalSeparator() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.PREMIUM_MONTHLY_PRICE, "9,90")).isEqualTo("9.90");
        assertRefused(RuntimeSetting.PREMIUM_MONTHLY_PRICE, "grátis", "admin.settings.value.not-number");
        assertRefused(RuntimeSetting.PREMIUM_MONTHLY_PRICE, "0", "admin.settings.value.out-of-range");
    }

    @Test
    void toggle_acceptsOnlyTrueOrFalse() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.SUBSCRIPTION_ENFORCE, "TRUE")).isEqualTo("true");
        assertRefused(RuntimeSetting.SUBSCRIPTION_ENFORCE, "sim", "admin.settings.value.not-boolean");
    }

    @Test
    void date_isIsoOrBlankForNoDate() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.LAUNCH_PROMO_UNTIL, "2027-03-31")).isEqualTo("2027-03-31");
        assertThat(SettingValueCodec.normalize(RuntimeSetting.LAUNCH_PROMO_UNTIL, "  ")).isEmpty();
        assertRefused(RuntimeSetting.LAUNCH_PROMO_UNTIL, "31/03/2027", "admin.settings.value.not-date");
    }

    @Test
    void ufList_isUpperCasedDeduplicatedAndChecked() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.PAUSED_STATES, "se, to ,SE")).isEqualTo("SE,TO");
        assertThat(SettingValueCodec.normalize(RuntimeSetting.PAUSED_STATES, "")).isEmpty();
        assertRefused(RuntimeSetting.PAUSED_STATES, "SE,XX", "admin.settings.value.unknown-uf");
    }

    @Test
    void integerList_needsAtLeastOneNumberInRange() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS, "15, 7,3 ,1")).isEqualTo("15,7,3,1");
        assertRefused(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS, "", "admin.settings.value.empty-list");
        assertRefused(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS, "15,0", "admin.settings.value.out-of-range");
    }

    @Test
    void option_matchesIgnoringCase_andOptionListAcceptsNone() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.RELEVANCE_MODE, "shadow")).isEqualTo("SHADOW");
        assertRefused(RuntimeSetting.RELEVANCE_MODE, "MAYBE", "admin.settings.value.unknown-option");
        assertThat(SettingValueCodec.normalize(RuntimeSetting.FREE_OPEN_FEATURES, "csv_export, PHOTO_EXTRACTION"))
                .isEqualTo("CSV_EXPORT,PHOTO_EXTRACTION");
        assertThat(SettingValueCodec.normalize(RuntimeSetting.FREE_OPEN_FEATURES, "")).isEmpty();
    }

    @Test
    void text_isBoundedByItsLength() {
        assertThat(SettingValueCodec.normalize(RuntimeSetting.APP_MIN_VERSION, " 1.4.0 ")).isEqualTo("1.4.0");
        assertRefused(RuntimeSetting.APP_MIN_VERSION, "1".repeat(21), "admin.settings.value.too-long");
    }

    @Test
    void jsonValue_isTypedForTheApps() {
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.PRICE_WINDOW_DAYS, "90")).isEqualTo(90L);
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.NEARBY_RADIUS_KM, "20.5")).isEqualTo(new BigDecimal("20.5"));
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.SUBSCRIPTION_ENFORCE, "true")).isEqualTo(true);
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.PAUSED_STATES, "SE,TO")).isEqualTo(List.of("SE", "TO"));
        assertThat(SettingValueCodec.toJsonValue(RuntimeSetting.LAUNCH_PROMO_UNTIL, "")).isNull();
    }

    private static void assertRefused(RuntimeSetting setting, String typedValue, String messageKey) {
        assertThatThrownBy(() -> SettingValueCodec.normalize(setting, typedValue))
                .isInstanceOfSatisfying(InvalidSettingValueException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo(messageKey));
    }
}
