package com.relyon.economizaai.service.settings;

import java.util.EnumMap;
import java.util.Map;

/** Binds every setting to a plain map, standing in for the code that uses the settings. */
class InMemorySettingBinder implements SettingBinder {

    final Map<RuntimeSetting, String> valuesInUse = new EnumMap<>(RuntimeSetting.class);

    InMemorySettingBinder() {
        valuesInUse.put(RuntimeSetting.PRICE_WINDOW_DAYS, "90");
        valuesInUse.put(RuntimeSetting.NEARBY_RADIUS_KM, "20");
        valuesInUse.put(RuntimeSetting.SUBSCRIPTION_ENFORCE, "false");
        valuesInUse.put(RuntimeSetting.LAUNCH_PROMO_UNTIL, "2026-12-31");
        valuesInUse.put(RuntimeSetting.PAUSED_STATES, "SE,TO");
        valuesInUse.put(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS, "15,7,3,1");
    }

    @Override
    public void bind(SettingBindings bindings) {
        for (var setting : RuntimeSetting.values()) {
            valuesInUse.putIfAbsent(setting, anyAcceptedValue(setting));
            bindings.text(setting, () -> valuesInUse.get(setting), value -> valuesInUse.put(setting, value));
        }
    }

    private static String anyAcceptedValue(RuntimeSetting setting) {
        var spec = setting.getSpec();
        return switch (spec.type()) {
            case INTEGER, DECIMAL, INTEGER_LIST -> spec.min().toPlainString();
            case BOOLEAN -> "false";
            case OPTION -> spec.options().getFirst();
            case TEXT, DATE, UF_LIST, OPTION_LIST -> "";
        };
    }
}
