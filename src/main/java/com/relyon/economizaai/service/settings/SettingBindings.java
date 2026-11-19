package com.relyon.economizaai.service.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Where each setting lives in the running application: how to read the value in effect and how
 * to put a new one in place. Values travel as the setting's canonical text; the typed helpers
 * convert at the edge.
 */
public class SettingBindings {

    /** Reads and replaces one setting's value, in canonical text. */
    public record Binding(Supplier<String> currentValue, Consumer<String> replaceValue) {}

    private final Map<RuntimeSetting, Binding> bindings = new EnumMap<>(RuntimeSetting.class);

    public void integer(RuntimeSetting setting, IntSupplier currentValue, IntConsumer replaceValue) {
        register(setting, () -> String.valueOf(currentValue.getAsInt()),
                canonical -> replaceValue.accept(Integer.parseInt(canonical)));
    }

    public void wholeNumber(RuntimeSetting setting, LongSupplier currentValue, LongConsumer replaceValue) {
        register(setting, () -> String.valueOf(currentValue.getAsLong()),
                canonical -> replaceValue.accept(Long.parseLong(canonical)));
    }

    public void decimal(RuntimeSetting setting, Supplier<BigDecimal> currentValue, Consumer<BigDecimal> replaceValue) {
        register(setting, () -> currentValue.get().toPlainString(),
                canonical -> replaceValue.accept(new BigDecimal(canonical)));
    }

    public void fraction(RuntimeSetting setting, DoubleSupplier currentValue, DoubleConsumer replaceValue) {
        register(setting, () -> BigDecimal.valueOf(currentValue.getAsDouble()).stripTrailingZeros().toPlainString(),
                canonical -> replaceValue.accept(Double.parseDouble(canonical)));
    }

    public void toggle(RuntimeSetting setting, BooleanSupplier currentValue, Consumer<Boolean> replaceValue) {
        register(setting, () -> String.valueOf(currentValue.getAsBoolean()),
                canonical -> replaceValue.accept(Boolean.parseBoolean(canonical)));
    }

    /** Free text, an option or a comma-separated list — the code that uses it reads the text as is. */
    public void text(RuntimeSetting setting, Supplier<String> currentValue, Consumer<String> replaceValue) {
        register(setting, () -> currentValue.get() == null ? "" : currentValue.get(), replaceValue);
    }

    public void date(RuntimeSetting setting, Supplier<LocalDate> currentValue, Consumer<LocalDate> replaceValue) {
        register(setting, () -> currentValue.get() == null ? "" : currentValue.get().toString(),
                canonical -> replaceValue.accept(canonical.isEmpty() ? null : LocalDate.parse(canonical)));
    }

    public void integerList(RuntimeSetting setting, Supplier<List<Integer>> currentValue,
                            Consumer<List<Integer>> replaceValue) {
        register(setting,
                () -> currentValue.get().stream().map(String::valueOf).collect(Collectors.joining(",")),
                canonical -> replaceValue.accept(
                        SettingValueCodec.splitList(canonical).stream().map(Integer::valueOf).toList()));
    }

    public Binding of(RuntimeSetting setting) {
        return bindings.get(setting);
    }

    public boolean isBound(RuntimeSetting setting) {
        return bindings.containsKey(setting);
    }

    private void register(RuntimeSetting setting, Supplier<String> currentValue, Consumer<String> replaceValue) {
        if (bindings.putIfAbsent(setting, new Binding(currentValue, replaceValue)) != null) {
            throw new IllegalStateException("Setting bound twice: " + setting);
        }
    }
}
