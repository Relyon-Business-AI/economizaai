package com.relyon.economizaai.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lens translation table: every lens must reduce to (segment set,
 * inclusion/exclusion, legacy query mode) exactly — the query sites rely on
 * these three alone and never enumerate lenses.
 */
class MarketScopeTest {

    @Test
    void marketLensExcludesPharmacy() {
        assertEquals(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.FOOD_RETAIL),
                MarketScope.MARKET.segments());
        assertFalse(MarketScope.MARKET.segments().contains(MerchantSegment.PHARMACY));
    }

    @Test
    void singleSegmentLenses() {
        assertEquals(Set.of(MerchantSegment.PHARMACY), MarketScope.PHARMACY.segments());
        assertEquals(Set.of(MerchantSegment.GAS_STATION), MarketScope.FUEL.segments());
    }

    @Test
    void othersExcludesAllFourVerticalSegments() {
        assertTrue(MarketScope.OTHERS.exclusion());
        assertEquals(Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.FOOD_RETAIL,
                MerchantSegment.PHARMACY, MerchantSegment.GAS_STATION), MarketScope.OTHERS.segments());
    }

    @Test
    void legacyScopesKeepGroceryPlusPharmacySet() {
        var legacySet = Set.of(MerchantSegment.SUPERMARKET, MerchantSegment.PHARMACY, MerchantSegment.FOOD_RETAIL);
        assertEquals(legacySet, MarketScope.SUPPORTED.segments());
        assertEquals(legacySet, MarketScope.OTHER.segments());
        assertFalse(MarketScope.SUPPORTED.exclusion());
        assertTrue(MarketScope.OTHER.exclusion());
    }

    @Test
    void inclusionIsTrueOnlyForInclusionLenses() {
        assertTrue(MarketScope.MARKET.inclusion());
        assertTrue(MarketScope.PHARMACY.inclusion());
        assertTrue(MarketScope.FUEL.inclusion());
        assertTrue(MarketScope.SUPPORTED.inclusion());
        assertFalse(MarketScope.ALL.inclusion());
        assertFalse(MarketScope.OTHER.inclusion());
        assertFalse(MarketScope.OTHERS.inclusion());
    }

    @Test
    void queryModeMapsEveryLensToTheThreeLegacyModes() {
        assertEquals("ALL", MarketScope.ALL.queryMode());
        assertEquals("SUPPORTED", MarketScope.SUPPORTED.queryMode());
        assertEquals("SUPPORTED", MarketScope.MARKET.queryMode());
        assertEquals("SUPPORTED", MarketScope.PHARMACY.queryMode());
        assertEquals("SUPPORTED", MarketScope.FUEL.queryMode());
        assertEquals("OTHER", MarketScope.OTHER.queryMode());
        assertEquals("OTHER", MarketScope.OTHERS.queryMode());
    }
}
