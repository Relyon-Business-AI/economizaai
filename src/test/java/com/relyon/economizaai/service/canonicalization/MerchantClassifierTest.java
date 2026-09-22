package com.relyon.economizaai.service.canonicalization;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.repository.MarketLocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantClassifierTest {

    @Mock private MarketLocationRepository marketLocationRepository;
    @InjectMocks private MerchantClassifier classifier;

    private void noStoredSegment() {
        lenient().when(marketLocationRepository.findByCnpj(any())).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DIMED S/A DISTRIBUIDORA DE MEDICAMENTOS",
            "DROGARIA SAO JOAO LTDA",
            "FARMACIA E DROGARIA NISSEI",
            "PANVEL FARMACIAS",
            "RAIA DROGASIL S.A.",
            "DROGÃO SUPER"
    })
    void recognizesPharmaciesByName(String name) {
        noStoredSegment();
        assertTrue(classifier.isPharmacy(null, name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA",
            "BISTEK SUPERMERCADOS LTDA",
            "SUPERMERCADO SAO JOAO LTDA",
            "ATACADAO S.A."
    })
    void doesNotFlagSupermarketsByName(String name) {
        noStoredSegment();
        assertFalse(classifier.isPharmacy(null, name), name);
    }

    @Test
    void verifiedPharmacySegmentWins() {
        var market = MarketLocation.builder().cnpj("123").segment(MerchantSegment.PHARMACY).build();
        when(marketLocationRepository.findByCnpj("123")).thenReturn(Optional.of(market));
        // name looks like a supermarket, but CNAE says pharmacy → pharmacy
        assertTrue(classifier.isPharmacy("123", "MERCADO CENTRAL"));
    }

    @Test
    void verifiedSupermarketSegmentOverridesNameGuess() {
        var market = MarketLocation.builder().cnpj("123").segment(MerchantSegment.SUPERMARKET).build();
        when(marketLocationRepository.findByCnpj("123")).thenReturn(Optional.of(market));
        // name contains "FARMACIA" but CNAE verified it as a supermarket → not pharmacy
        assertFalse(classifier.isPharmacy("123", "SUPER FARMACIA E MERCADO"));
    }

    @Test
    void unknownSegmentFallsBackToName() {
        var market = MarketLocation.builder().cnpj("123").segment(MerchantSegment.UNKNOWN).build();
        when(marketLocationRepository.findByCnpj("123")).thenReturn(Optional.of(market));
        assertTrue(classifier.isPharmacy("123", "DROGARIA SAO JOAO"));
    }

    @Test
    void nullOrBlankNameWithNoSegmentIsNotPharmacy() {
        noStoredSegment();
        assertFalse(classifier.isPharmacy(null, null));
        assertFalse(classifier.isPharmacy(null, "  "));
    }
}
