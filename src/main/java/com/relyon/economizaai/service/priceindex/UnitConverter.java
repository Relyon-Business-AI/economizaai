package com.relyon.economizaai.service.priceindex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Converts Brazilian receipt units to a canonical {@link BaseUnit} so prices
 * across different pack sizes / spellings can be compared apples-to-apples.
 *
 * <p>The actual normalization a downstream consumer cares about is
 * {@link #normalizeItemPrice} — given an item's (quantity, unit, packSize,
 * packUnit, totalPrice) it returns "price per base unit" (e.g. R$/L of milk).
 *
 * <p>Single responsibility: unit math. No DB, no entity awareness.
 */
public final class UnitConverter {

    public enum BaseUnit { KG, L, UN }

    /** A unit string mapped to its base unit + multiplier (units of the source equal multiplier of the base). */
    public record UnitFactor(BaseUnit baseUnit, BigDecimal multiplier) {}

    /** A computed normalized price tied to its base unit. */
    public record NormalizedPrice(BaseUnit baseUnit, BigDecimal pricePerBaseUnit) {}

    private static final BigDecimal MILLI = new BigDecimal("0.001");

    private static final Map<String, UnitFactor> CONVERSIONS = Map.ofEntries(
            // Mass
            Map.entry("kg",  new UnitFactor(BaseUnit.KG, new BigDecimal("1"))),
            Map.entry("kgr", new UnitFactor(BaseUnit.KG, new BigDecimal("1"))),
            Map.entry("k",   new UnitFactor(BaseUnit.KG, new BigDecimal("1"))),
            Map.entry("g",   new UnitFactor(BaseUnit.KG, MILLI)),
            Map.entry("gr",  new UnitFactor(BaseUnit.KG, MILLI)),
            Map.entry("grs", new UnitFactor(BaseUnit.KG, MILLI)),
            Map.entry("mg",  new UnitFactor(BaseUnit.KG, new BigDecimal("0.000001"))),
            // Volume
            Map.entry("l",   new UnitFactor(BaseUnit.L,  new BigDecimal("1"))),
            Map.entry("lt",  new UnitFactor(BaseUnit.L,  new BigDecimal("1"))),
            Map.entry("lts", new UnitFactor(BaseUnit.L,  new BigDecimal("1"))),
            Map.entry("ml",  new UnitFactor(BaseUnit.L,  MILLI)),
            Map.entry("cl",  new UnitFactor(BaseUnit.L,  new BigDecimal("0.01"))),
            // Discrete count — common Portuguese pack synonyms
            Map.entry("un",  new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("und", new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("unid",new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("pc",  new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("pct", new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("cx",  new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("fd",  new UnitFactor(BaseUnit.UN, new BigDecimal("1"))),
            Map.entry("pac", new UnitFactor(BaseUnit.UN, new BigDecimal("1")))
    );

    private UnitConverter() {}

    public static Optional<UnitFactor> factorFor(String unit) {
        if (unit == null) return Optional.empty();
        var key = unit.trim().toLowerCase();
        if (key.isEmpty()) return Optional.empty();
        return Optional.ofNullable(CONVERSIONS.get(key));
    }

    /**
     * Returns price-per-base-unit when we can confidently normalize this
     * item, else empty (caller should fall back to the raw unit price).
     *
     * <p>Two paths:
     * <ol>
     *   <li>Item unit is itself a base unit (e.g. "kg") — divide totalPrice
     *       by quantity converted to base.</li>
     *   <li>Item unit is a discrete count ("UN", "CX") AND product carries
     *       a recognized pack size + pack unit — divide totalPrice by
     *       (quantity × packSize) converted to base.</li>
     * </ol>
     * Returns empty when either path is missing data we'd need.
     */
    public static Optional<NormalizedPrice> normalizeItemPrice(BigDecimal quantity,
                                                               String unit,
                                                               BigDecimal packSize,
                                                               String packUnit,
                                                               BigDecimal totalPrice) {
        if (quantity == null || quantity.signum() <= 0 || totalPrice == null || totalPrice.signum() <= 0) {
            return Optional.empty();
        }
        var itemFactor = factorFor(unit);
        // Path 1: item unit is a recognized base (kg, L, etc.) — pack info ignored.
        if (itemFactor.isPresent() && itemFactor.get().baseUnit() != BaseUnit.UN) {
            var totalInBase = quantity.multiply(itemFactor.get().multiplier());
            if (totalInBase.signum() <= 0) return Optional.empty();
            return Optional.of(new NormalizedPrice(itemFactor.get().baseUnit(),
                    totalPrice.divide(totalInBase, 4, RoundingMode.HALF_UP)));
        }
        // Path 2: item is a discrete count — need product packSize × packUnit.
        if (packSize == null || packSize.signum() <= 0) return Optional.empty();
        var packFactor = factorFor(packUnit);
        if (packFactor.isEmpty()) return Optional.empty();
        var totalInBase = quantity.multiply(packSize).multiply(packFactor.get().multiplier());
        if (totalInBase.signum() <= 0) return Optional.empty();
        return Optional.of(new NormalizedPrice(packFactor.get().baseUnit(),
                totalPrice.divide(totalInBase, 4, RoundingMode.HALF_UP)));
    }
}
