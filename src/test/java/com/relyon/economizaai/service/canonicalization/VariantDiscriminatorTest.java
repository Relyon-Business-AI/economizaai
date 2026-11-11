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

    @Test
    void sugarType_demeraraVsRefinado_differentVariants() {
        // "demerara" present on one side, the refined/plain sugar carries no type marker
        // — a variant-line token, so present-on-one-side-only splits them (unlike a flavor).
        assertFalse(VariantDiscriminator.sameVariant(
                "ACUCAR DEMERARA 1KG", "ACUCAR REFINADO 1KG"));
        assertFalse(VariantDiscriminator.sameVariant(
                "ACUCAR DEMERARA 1KG", "ACUCAR 1KG"));
    }

    @Test
    void sameSugarType_demerara_sameVariant() {
        assertTrue(VariantDiscriminator.sameVariant(
                "ACUCAR DEMERARA 1KG", "ACUC DEMERARA 1 KG"));
    }

    @Test
    void detergentLine_platinumVsFlavor_differentVariants() {
        // Det Ypê maçã vs Det Ypê platinum: "platinum" is a line marker (present-on-one-
        // side splits); "maca" (maçã) is a weak flavor. The line marker forces the split.
        assertFalse(VariantDiscriminator.sameVariant(
                "DET YPE MACA 500ML", "DET YPE PLATINUM 500ML"));
        assertFalse(VariantDiscriminator.sameVariant(
                "DET YPE PLATINUM 500ML", "DET YPE 500ML"));
    }

    @Test
    void pastaShape_parafusoVsEspaguete_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "MAC GALO PARAFUSO 500G", "MAC GALO ESPAGUETE 500G"));
    }

    @Test
    void tangFlavors_guaranaVsMaracuja_differentVariants() {
        assertFalse(VariantDiscriminator.sameVariant(
                "TANG GUARANA 25G", "TANG MARACUJA 25G"));
    }

    @Test
    void variantLineTokenIsTaggedStrong() {
        var signature = VariantDiscriminator.signature("ACUCAR DEMERARA 1KG");
        assertTrue(signature.contains("line_demerara"));
    }
}
