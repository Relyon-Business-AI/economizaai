package com.relyon.economizaai.service.canonicalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantDiscriminatorTest {

    @Test
    void divineBug_zeroPercentVsAoLeite_areDifferentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "CHOC DIVINE 50% ZERO 70G",
                "CHOCOLATE DIVINE 70G AO LEITE SGLUT"));
    }

    @Test
    void sameBaseNameNoDistinguishingTokens_sameVariant() {
        assertTrue(VariantDiscriminator.sameVariant(
                "ARROZ TIO JOAO 5KG", "ARROZ TIO J 5KG"));
    }

    @Test
    void percentageDifference_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "LEITE 50% MENOS LACTOSE", "LEITE INTEGRAL"));
    }

    @Test
    void samePercentage_sameVariant() {
        assertTrue(VariantDiscriminator.sameVariant(
                "IOGURTE 0% GORDURA MORANGO", "IOG 0 % MORANGO"));
    }

    @Test
    void sugarMarkerVariants_collapseToSameTag() {
        // "sem acucar", "s/acucar" and "zero acucar" are the same variant
        assertTrue(VariantDiscriminator.sameVariant(
                "SUCO SEM ACUCAR 1L", "SUCO S/ACUCAR 1L"));
        assertTrue(VariantDiscriminator.sameVariant(
                "SUCO ZERO ACUCAR 1L", "SUCO SEM ACUCAR 1L"));
    }

    @Test
    void sugarVsNormal_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "REFRIGERANTE ZERO ACUCAR 2L", "REFRIGERANTE 2L"));
    }

    @Test
    void dietaryMarker_semGlutenVsPlain_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "BOLO SEM GLUTEN 300G", "BOLO 300G"));
        assertFalse(VariantDiscriminator.sameVariant(
                "PAO SGLUT", "PAO INTEGRAL"));
    }

    @Test
    void flavorDifference_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "IOGURTE MORANGO 170G", "IOGURTE COCO 170G"));
    }

    @Test
    void sameFlavor_sameVariant() {
        assertTrue(VariantDiscriminator.sameVariant(
                "IOGURTE MORANGO 170G", "IOG MORANGO 170 G"));
    }

    @Test
    void blankInputsHaveEmptySignatures_sameVariant() {
        assertTrue(VariantDiscriminator.sameVariant(null, ""));
        assertTrue(VariantDiscriminator.sameVariant("", "   "));
    }

    @Test
    void signatureCapturesPercentageAndSugarTag() {
        var signature = VariantDiscriminator.signature("CHOC 50% ZERO ACUCAR 70G");
        assertTrue(signature.contains("pct50"));
        assertTrue(signature.contains("no_sugar"));
        // "zero" is subsumed by the "zero acucar" phrase tag (no double-count)
        assertFalse(signature.contains("zero"));
    }

    @Test
    void standaloneZero_isDistinguishing() {
        var signature = VariantDiscriminator.signature("REFRIGERANTE ZERO 2L");
        assertTrue(signature.contains("zero"));
    }
}
