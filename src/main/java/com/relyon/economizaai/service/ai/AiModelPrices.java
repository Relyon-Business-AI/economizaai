package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.exception.InvalidSettingValueException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What each AI model costs, in US$ per million tokens (input / output), used to ESTIMATE the
 * AI spend shown in the admin — it never changes what the provider actually bills.
 *
 * <p>Written as one line of text: {@code model=input/output} entries separated by {@code ;},
 * plus one {@code default=input/output} entry for any model that is not listed, e.g.
 * {@code claude-haiku-4-5=1/5;gpt-5-mini=0.25/2;default=3/15}.
 */
public final class AiModelPrices {

    private static final String ENTRY_SEPARATOR = ";";
    private static final String DEFAULT_ENTRY = "default";
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final BigDecimal HIGHEST_PRICE = BigDecimal.valueOf(100_000);
    private static final double TOKENS_PER_PRICE_UNIT = 1_000_000.0;

    /** US$ per million tokens. */
    public record ModelPrice(BigDecimal input, BigDecimal output) {

        private String asText() {
            return plain(input) + "/" + plain(output);
        }

        private static String plain(BigDecimal price) {
            return price.signum() == 0 ? "0" : price.stripTrailingZeros().toPlainString();
        }
    }

    private final Map<String, ModelPrice> pricesByModel;
    private final ModelPrice priceOfUnlistedModels;

    private AiModelPrices(Map<String, ModelPrice> pricesByModel, ModelPrice priceOfUnlistedModels) {
        this.pricesByModel = pricesByModel;
        this.priceOfUnlistedModels = priceOfUnlistedModels;
    }

    /** Reads the table from its text; refuses anything that is not exactly in the documented format. */
    public static AiModelPrices parse(String settingLabel, String text) {
        var pricesByModel = new LinkedHashMap<String, ModelPrice>();
        ModelPrice priceOfUnlistedModels = null;
        for (var rawEntry : (text == null ? "" : text).split(ENTRY_SEPARATOR)) {
            var entry = rawEntry.trim();
            if (entry.isEmpty()) continue;
            var nameAndPrices = entry.split("=", -1);
            if (nameAndPrices.length != 2) throw badEntry(settingLabel, entry);
            var model = nameAndPrices[0].trim();
            var price = priceOf(settingLabel, entry, nameAndPrices[1]);
            if (DEFAULT_ENTRY.equalsIgnoreCase(model)) {
                if (priceOfUnlistedModels != null) throw repeated(settingLabel, DEFAULT_ENTRY);
                priceOfUnlistedModels = price;
            } else {
                if (!MODEL_NAME.matcher(model).matches()) throw badEntry(settingLabel, entry);
                if (pricesByModel.putIfAbsent(model, price) != null) throw repeated(settingLabel, model);
            }
        }
        if (priceOfUnlistedModels == null) {
            throw new InvalidSettingValueException("admin.settings.value.model-prices.no-default", settingLabel);
        }
        return new AiModelPrices(pricesByModel, priceOfUnlistedModels);
    }

    /** The table as canonical text, for the admin: checks the format and tidies spaces and numbers. */
    public static String canonicalText(String settingLabel, String typed) {
        return parse(settingLabel, typed).asText();
    }

    public String asText() {
        var listed = pricesByModel.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().asText() + ENTRY_SEPARATOR)
                .collect(Collectors.joining());
        return listed + DEFAULT_ENTRY + "=" + priceOfUnlistedModels.asText();
    }

    public ModelPrice priceOf(String model) {
        return model == null ? priceOfUnlistedModels : pricesByModel.getOrDefault(model, priceOfUnlistedModels);
    }

    public BigDecimal estimateCostUsd(String model, long inputTokens, long outputTokens) {
        var price = priceOf(model);
        var cost = (inputTokens * price.input().doubleValue() + outputTokens * price.output().doubleValue())
                / TOKENS_PER_PRICE_UNIT;
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }

    private static ModelPrice priceOf(String settingLabel, String entry, String pricesText) {
        var inputAndOutput = pricesText.split("/", -1);
        if (inputAndOutput.length != 2) throw badEntry(settingLabel, entry);
        return new ModelPrice(amountOf(settingLabel, entry, inputAndOutput[0]),
                amountOf(settingLabel, entry, inputAndOutput[1]));
    }

    private static BigDecimal amountOf(String settingLabel, String entry, String amountText) {
        try {
            var amount = new BigDecimal(amountText.trim().replace(',', '.'));
            if (amount.signum() < 0 || amount.compareTo(HIGHEST_PRICE) > 0) throw badEntry(settingLabel, entry);
            return amount;
        } catch (NumberFormatException notANumber) {
            throw badEntry(settingLabel, entry);
        }
    }

    private static InvalidSettingValueException badEntry(String settingLabel, String entry) {
        return new InvalidSettingValueException("admin.settings.value.model-prices.bad-entry", settingLabel, entry);
    }

    private static InvalidSettingValueException repeated(String settingLabel, String model) {
        return new InvalidSettingValueException("admin.settings.value.model-prices.repeated", settingLabel, model);
    }
}
