package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.exception.InvalidSettingValueException;
import com.relyon.economizaai.exception.SettingNotFoundException;
import com.relyon.economizaai.model.RuntimeSettingOverride;
import com.relyon.economizaai.repository.RuntimeSettingOverrideRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The business settings the owners change from the admin without a deploy. Every setting starts
 * at the value configured for the environment (application.yaml / env var); a value saved in
 * the admin replaces it at once and survives restarts, and removing it restores the configured
 * one. The code that uses a setting keeps reading it where it always did — the new value is put
 * in place there (see {@link SettingBinder}).
 */
@Slf4j
@Service
public class RuntimeSettings {

    /** A setting as the admin shows it. */
    public record SettingState(RuntimeSetting setting, String value, String configuredValue, boolean changed,
                               LocalDateTime changedAt, String changedBy) {}

    private record SavedValue(String value, LocalDateTime savedAt, String savedBy) {}

    private final RuntimeSettingOverrideRepository overrideRepository;
    private final SettingBindings bindings = new SettingBindings();
    private final Map<RuntimeSetting, String> configuredValues = new EnumMap<>(RuntimeSetting.class);
    private final Map<RuntimeSetting, SavedValue> savedValues = new ConcurrentHashMap<>();

    public RuntimeSettings(RuntimeSettingOverrideRepository overrideRepository, List<SettingBinder> binders) {
        this.overrideRepository = overrideRepository;
        binders.forEach(binder -> binder.bind(bindings));
        requireEverySettingBound();
        rememberConfiguredValues();
    }

    @PostConstruct
    void applySavedValuesAtStartup() {
        refreshFromDatabase();
        log.info("runtime_settings.loaded settings={} changed_in_admin={}",
                RuntimeSetting.values().length, savedValues.size());
    }

    /**
     * Picks up values saved by another instance (or straight in the database) and drops the ones
     * that were removed there.
     */
    @Scheduled(fixedDelayString = "${economizaai.settings.refresh-ms:60000}",
            initialDelayString = "${economizaai.settings.refresh-ms:60000}")
    public void refreshFromDatabase() {
        var stored = new EnumMap<RuntimeSetting, SavedValue>(RuntimeSetting.class);
        for (var override : overrideRepository.findAll()) {
            RuntimeSetting.byKey(override.getSettingKey()).ifPresentOrElse(
                    setting -> acceptedValue(setting, override).ifPresent(saved -> stored.put(setting, saved)),
                    () -> log.warn("runtime_settings.unknown_key key={}", override.getSettingKey()));
        }
        for (var setting : RuntimeSetting.values()) {
            putInEffect(setting, stored.get(setting));
        }
    }

    public List<SettingState> all() {
        return Arrays.stream(RuntimeSetting.values()).map(this::stateOf).toList();
    }

    public SettingState stateOf(RuntimeSetting setting) {
        var saved = savedValues.get(setting);
        var configured = configuredValues.get(setting);
        return saved == null
                ? new SettingState(setting, configured, configured, false, null, null)
                : new SettingState(setting, saved.value(), configured, true, saved.savedAt(), saved.savedBy());
    }

    /** Saves a value typed in the admin and puts it in effect at once. */
    public SettingState change(String settingKey, String typedValue, String adminEmail) {
        var setting = settingByKey(settingKey);
        var value = SettingValueCodec.normalize(setting, typedValue);
        var override = overrideRepository.findBySettingKey(setting.getKey())
                .orElseGet(() -> RuntimeSettingOverride.builder().settingKey(setting.getKey()).build());
        override.setValue(value);
        override.setUpdatedBy(adminEmail);
        var stored = overrideRepository.save(override);
        putInEffect(setting, new SavedValue(value, savedAtOf(stored), adminEmail));
        log.info("runtime_settings.changed key={} value={} by={}", setting.getKey(), value, adminEmail);
        return stateOf(setting);
    }

    /** Removes the value saved in the admin: the one configured for the environment is back in effect. */
    public SettingState restoreConfigured(String settingKey, String adminEmail) {
        var setting = settingByKey(settingKey);
        overrideRepository.findBySettingKey(setting.getKey()).ifPresent(overrideRepository::delete);
        putInEffect(setting, null);
        log.info("runtime_settings.restored key={} value={} by={}",
                setting.getKey(), configuredValues.get(setting), adminEmail);
        return stateOf(setting);
    }

    /** The values the apps need, by the name each is published under, in JSON-friendly form. */
    public Map<String, Object> publishedToApps() {
        var published = new LinkedHashMap<String, Object>();
        for (var setting : RuntimeSetting.values()) {
            var appKey = setting.getSpec().appKey();
            if (appKey != null) {
                published.put(appKey, SettingValueCodec.toJsonValue(setting, stateOf(setting).value()));
            }
        }
        return published;
    }

    private void putInEffect(RuntimeSetting setting, SavedValue saved) {
        var previous = saved == null ? savedValues.remove(setting) : savedValues.put(setting, saved);
        var valueBefore = previous == null ? configuredValues.get(setting) : previous.value();
        var valueNow = saved == null ? configuredValues.get(setting) : saved.value();
        if (!Objects.equals(valueBefore, valueNow)) {
            bindings.of(setting).replaceValue().accept(valueNow);
        }
    }

    private void requireEverySettingBound() {
        var unbound = Arrays.stream(RuntimeSetting.values()).filter(setting -> !bindings.isBound(setting)).toList();
        if (!unbound.isEmpty()) {
            throw new IllegalStateException("Settings with no binding to the code that uses them: " + unbound);
        }
    }

    private void rememberConfiguredValues() {
        for (var setting : RuntimeSetting.values()) {
            configuredValues.put(setting, bindings.of(setting).currentValue().get());
        }
    }

    private RuntimeSetting settingByKey(String settingKey) {
        return RuntimeSetting.byKey(settingKey).orElseThrow(() -> new SettingNotFoundException(settingKey));
    }

    /** A stored value the setting no longer accepts (range tightened, row edited by hand) is ignored, not applied. */
    private Optional<SavedValue> acceptedValue(RuntimeSetting setting, RuntimeSettingOverride override) {
        try {
            var value = SettingValueCodec.normalize(setting, override.getValue());
            return Optional.of(new SavedValue(value, savedAtOf(override), override.getUpdatedBy()));
        } catch (InvalidSettingValueException refused) {
            log.warn("runtime_settings.stored_value_refused key={} reason={}", setting.getKey(), refused.getMessageKey());
            return Optional.empty();
        }
    }

    private static LocalDateTime savedAtOf(RuntimeSettingOverride override) {
        return override.getUpdatedAt() == null ? LocalDateTime.now() : override.getUpdatedAt();
    }
}
