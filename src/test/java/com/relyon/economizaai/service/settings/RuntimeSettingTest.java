package com.relyon.economizaai.service.settings;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSettingTest {

    @Test
    void everySettingHasItsOwnKey_andItsOwnNameInTheApps() {
        var keys = Arrays.stream(RuntimeSetting.values()).map(RuntimeSetting::getKey).toList();
        var appKeys = Arrays.stream(RuntimeSetting.values())
                .map(setting -> setting.getSpec().appKey()).filter(Objects::nonNull).toList();

        assertThat(keys).doesNotHaveDuplicates().allMatch(key -> key.startsWith("economizaai."));
        assertThat(appKeys).doesNotHaveDuplicates();
    }

    @Test
    void everySettingIsExplainedToTheAdmin() {
        assertThat(RuntimeSetting.values()).allSatisfy(setting -> {
            assertThat(setting.getLabel()).isNotBlank();
            assertThat(setting.getDescription()).isNotBlank();
            assertThat(RuntimeSettingReach.of(setting)).as("where %s shows up", setting).isNotBlank();
        });
    }

    @Test
    void everyOptionReadsInPortugueseInTheAdmin() {
        assertThat(RuntimeSetting.values()).allSatisfy(setting ->
                assertThat(setting.getSpec().options()).allSatisfy(option ->
                        assertThat(SettingOptionLabels.of(option)).as("label of %s", option).isNotBlank()));
    }

    @Test
    void byKey_findsASettingByItsConfigurationProperty() {
        assertThat(RuntimeSetting.byKey("economizaai.collaborative.lookback-days"))
                .contains(RuntimeSetting.PRICE_WINDOW_DAYS);
        assertThat(RuntimeSetting.byKey("economizaai.unknown")).isEmpty();
    }
}
