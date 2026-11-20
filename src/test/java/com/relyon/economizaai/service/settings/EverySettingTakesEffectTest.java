package com.relyon.economizaai.service.settings;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise behind the admin screen, checked against the real application: saving ANY setting
 * changes the value the running code reads (and the one the apps receive), and restoring it
 * brings the configured value back. A setting wired to nothing, or to a copy, fails here.
 */
@SpringBootTest
@ActiveProfiles("test")
class EverySettingTakesEffectTest {

    private static final String ADMIN = "settings-check@economizaai.app";

    @Autowired
    private RuntimeSettings runtimeSettings;

    @ParameterizedTest
    @EnumSource(RuntimeSetting.class)
    void savingInTheAdminChangesTheValueInUse(RuntimeSetting setting) {
        var configured = runtimeSettings.valueInUse(setting);
        var another = SettingValueCodec.normalize(setting, anotherValueFor(setting, configured));
        assertThat(another).as("test value for %s must differ from the configured one", setting).isNotEqualTo(configured);
        try {
            runtimeSettings.change(setting.getKey(), another, ADMIN);

            assertThat(inUse(setting)).as("value the code reads for %s", setting).isEqualTo(comparable(setting, another));
            var appKey = setting.getSpec().appKey();
            if (appKey != null) {
                assertThat(runtimeSettings.publishedToApps().get(appKey)).as("value sent to the apps as %s", appKey)
                        .isEqualTo(SettingValueCodec.toJsonValue(setting, another));
            }
        } finally {
            runtimeSettings.restoreConfigured(setting.getKey(), ADMIN);
        }
        assertThat(inUse(setting)).as("restored value of %s", setting).isEqualTo(comparable(setting, configured));
    }

    private Object inUse(RuntimeSetting setting) {
        return comparable(setting, runtimeSettings.valueInUse(setting));
    }

    /** Lists of states and of options are kept as sets by the code that uses them: order is not part of the value. */
    private static Object comparable(RuntimeSetting setting, String value) {
        var type = setting.getSpec().type();
        var unordered = type == SettingType.UF_LIST || type == SettingType.OPTION_LIST;
        return unordered ? Set.copyOf(SettingValueCodec.splitList(value)) : value;
    }

    private static String anotherValueFor(RuntimeSetting setting, String current) {
        var spec = setting.getSpec();
        return switch (spec.type()) {
            case INTEGER -> stepped(new BigDecimal(current), BigDecimal.ONE, spec).toPlainString();
            case DECIMAL -> stepped(new BigDecimal(current), new BigDecimal("0.01"), spec).toPlainString();
            case BOOLEAN -> String.valueOf(!Boolean.parseBoolean(current));
            case DATE -> current.isEmpty() ? "2031-01-01" : LocalDate.parse(current).plusDays(1).toString();
            case TEXT -> spec.format() == null ? current + "1" : current.replace("default=", "extra-model=1/2;default=");
            case INTEGER_LIST -> anotherList(current, spec);
            case UF_LIST -> toggled(current, "AC");
            case OPTION -> spec.options().stream().filter(option -> !option.equals(current)).findFirst().orElseThrow();
            case OPTION_LIST -> toggled(current, spec.options().get(0));
        };
    }

    private static BigDecimal stepped(BigDecimal current, BigDecimal step, SettingSpec spec) {
        var up = current.add(step);
        return spec.max() == null || up.compareTo(spec.max()) <= 0 ? up : current.subtract(step);
    }

    private static String anotherList(String current, SettingSpec spec) {
        var numbers = new ArrayList<>(SettingValueCodec.splitList(current));
        var first = numbers.isEmpty() ? spec.min() : new BigDecimal(numbers.get(0));
        var replacement = stepped(first, BigDecimal.ONE, spec).toPlainString();
        if (numbers.isEmpty()) numbers.add(replacement);
        else numbers.set(0, replacement);
        return String.join(",", numbers);
    }

    private static String toggled(String current, String item) {
        var items = new ArrayList<>(SettingValueCodec.splitList(current));
        if (!items.remove(item)) items.add(item);
        return String.join(",", List.copyOf(items));
    }
}
