package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.exception.InvalidSettingValueException;
import com.relyon.economizaai.exception.SettingNotFoundException;
import com.relyon.economizaai.model.RuntimeSettingOverride;
import com.relyon.economizaai.repository.RuntimeSettingOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingsTest {

    private static final String PRICE_WINDOW_KEY = RuntimeSetting.PRICE_WINDOW_DAYS.getKey();
    private static final String ADMIN = "admin@economizaai.app";

    @Mock private RuntimeSettingOverrideRepository overrideRepository;

    private InMemorySettingBinder binder;
    private RuntimeSettings settings;

    @BeforeEach
    void setUp() {
        binder = new InMemorySettingBinder();
        lenient().when(overrideRepository.save(any(RuntimeSettingOverride.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        settings = new RuntimeSettings(overrideRepository, List.of(binder));
    }

    @Test
    void untouchedSetting_showsTheConfiguredValue() {
        var state = settings.stateOf(RuntimeSetting.PRICE_WINDOW_DAYS);

        assertThat(state.value()).isEqualTo("90");
        assertThat(state.configuredValue()).isEqualTo("90");
        assertThat(state.changed()).isFalse();
    }

    @Test
    void change_putsTheValueInEffectAndStoresIt() {
        when(overrideRepository.findBySettingKey(PRICE_WINDOW_KEY)).thenReturn(Optional.empty());

        var state = settings.change(PRICE_WINDOW_KEY, " 60 ", ADMIN);

        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "60");
        assertThat(state.value()).isEqualTo("60");
        assertThat(state.configuredValue()).isEqualTo("90");
        assertThat(state.changed()).isTrue();
        assertThat(state.changedBy()).isEqualTo(ADMIN);
        verify(overrideRepository).save(any(RuntimeSettingOverride.class));
    }

    @Test
    void change_refusesAValueTheSettingDoesNotAccept_andKeepsTheOneInEffect() {
        assertThatThrownBy(() -> settings.change(PRICE_WINDOW_KEY, "5000", ADMIN))
                .isInstanceOf(InvalidSettingValueException.class);

        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "90");
        verify(overrideRepository, never()).save(any());
    }

    @Test
    void change_unknownSetting_isNotFound() {
        assertThatThrownBy(() -> settings.change("economizaai.no-such-setting", "1", ADMIN))
                .isInstanceOf(SettingNotFoundException.class);
    }

    @Test
    void restoreConfigured_bringsTheConfiguredValueBackAndDeletesTheStoredOne() {
        var stored = override(PRICE_WINDOW_KEY, "60");
        when(overrideRepository.findBySettingKey(PRICE_WINDOW_KEY)).thenReturn(Optional.empty(), Optional.of(stored));
        settings.change(PRICE_WINDOW_KEY, "60", ADMIN);

        var state = settings.restoreConfigured(PRICE_WINDOW_KEY, ADMIN);

        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "90");
        assertThat(state.changed()).isFalse();
        verify(overrideRepository).delete(stored);
    }

    @Test
    void refresh_appliesStoredValues_andDropsTheOnesRemovedFromTheDatabase() {
        when(overrideRepository.findAll()).thenReturn(List.of(override(PRICE_WINDOW_KEY, "45")));
        settings.refreshFromDatabase();
        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "45");

        when(overrideRepository.findAll()).thenReturn(List.of());
        settings.refreshFromDatabase();

        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "90");
        assertThat(settings.stateOf(RuntimeSetting.PRICE_WINDOW_DAYS).changed()).isFalse();
    }

    @Test
    void refresh_ignoresAStoredValueTheSettingNoLongerAccepts_andUnknownKeys() {
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(PRICE_WINDOW_KEY, "not-a-number"), override("economizaai.removed-setting", "1")));

        settings.refreshFromDatabase();

        assertThat(binder.valuesInUse).containsEntry(RuntimeSetting.PRICE_WINDOW_DAYS, "90");
        assertThat(settings.stateOf(RuntimeSetting.PRICE_WINDOW_DAYS).changed()).isFalse();
    }

    @Test
    void publishedToApps_carriesOnlyTheSettingsTheAppsRead_inJsonFriendlyForm() {
        var published = settings.publishedToApps();

        assertThat(published).containsEntry("priceWindowDays", 90L);
        assertThat(published).containsEntry("launchPromoUntil", "2026-12-31");
        assertThat(published).containsKeys("nearbyRadiusKm", "maxItemPhotos", "contingencyWindowHours");
        assertThat(published).containsKeys("freeMonthlyReceipts", "freeHistoryDays", "freeWatchedMarkets",
                "premiumMonthlyPrice", "premiumYearlyPrice");
        assertThat(published).doesNotContainKey(RuntimeSetting.SUBSCRIPTION_ENFORCE.getKey());
        assertThat(published).hasSize((int) Arrays.stream(RuntimeSetting.values())
                .filter(setting -> setting.getSpec().appKey() != null).count());
    }

    @Test
    void startup_failsWhenASettingHasNoBindingToTheCodeThatUsesIt() {
        SettingBinder bindsNothing = bindings -> { };

        assertThatThrownBy(() -> new RuntimeSettings(overrideRepository, List.of(bindsNothing)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRICE_WINDOW_DAYS");
    }

    private static RuntimeSettingOverride override(String key, String value) {
        return RuntimeSettingOverride.builder().settingKey(key).value(value).updatedBy(ADMIN).build();
    }
}
