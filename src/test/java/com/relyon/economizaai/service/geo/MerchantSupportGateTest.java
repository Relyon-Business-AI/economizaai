package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.service.geo.MerchantSupportGate.SupportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantSupportGateTest {

    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private CnpjActivityClient cnpjActivityClient;

    @InjectMocks private MerchantSupportGate gate;

    private MarketLocation market(MerchantSegment segment, MerchantSupportOverride override) {
        return MarketLocation.builder()
                .cnpj("11111111000111").cnpjRoot("11111111")
                .segment(segment).supportOverride(override)
                .build();
    }

    @Test
    void retailSegmentsAreSupported() {
        assertEquals(SupportStatus.SUPPORTED, gate.statusOf(market(MerchantSegment.SUPERMARKET, null)));
        assertEquals(SupportStatus.SUPPORTED, gate.statusOf(market(MerchantSegment.PHARMACY, null)));
        assertEquals(SupportStatus.SUPPORTED, gate.statusOf(market(MerchantSegment.FOOD_RETAIL, null)));
    }

    @Test
    void foodServiceIsBlocked() {
        assertEquals(SupportStatus.BLOCKED, gate.statusOf(market(MerchantSegment.FOOD_SERVICE, null)));
        assertTrue(gate.isBlocked(market(MerchantSegment.FOOD_SERVICE, null)));
    }

    @Test
    void otherAndUnknownAndUnregisteredAreGrey() {
        assertEquals(SupportStatus.GREY, gate.statusOf(market(MerchantSegment.OTHER, null)));
        assertEquals(SupportStatus.GREY, gate.statusOf(market(MerchantSegment.UNKNOWN, null)));
        assertEquals(SupportStatus.GREY, gate.statusOf(null));
    }

    @Test
    void adminOverrideBeatsSegment() {
        assertEquals(SupportStatus.SUPPORTED,
                gate.statusOf(market(MerchantSegment.OTHER, MerchantSupportOverride.SUPPORTED)));
        assertEquals(SupportStatus.BLOCKED,
                gate.statusOf(market(MerchantSegment.SUPERMARKET, MerchantSupportOverride.BLOCKED)));
    }

    @Test
    void isKnownBlockedCnpj_trueOnlyForRegisteredBlockedMerchant() {
        when(marketLocationRepository.findByCnpj("22222222000122"))
                .thenReturn(Optional.of(market(MerchantSegment.FOOD_SERVICE, null)));
        when(marketLocationRepository.findByCnpj("33333333000133")).thenReturn(Optional.empty());

        assertTrue(gate.isKnownBlockedCnpj("22222222000122"));
        assertFalse(gate.isKnownBlockedCnpj("33333333000133"), "first-time merchant passes submit");
        assertFalse(gate.isKnownBlockedCnpj(null));
    }

    @Test
    void contributesToIndex_supportedYes_greyNo_blockedNo() {
        when(cnpjActivityClient.isEnabled()).thenReturn(true);

        assertTrue(gate.contributesToIndex(market(MerchantSegment.SUPERMARKET, null)));
        assertTrue(gate.contributesToIndex(market(MerchantSegment.OTHER, MerchantSupportOverride.SUPPORTED)),
                "promoted grey merchant feeds the index");
        assertFalse(gate.contributesToIndex(market(MerchantSegment.OTHER, null)),
                "grey waits for admin review");
        assertFalse(gate.contributesToIndex(market(MerchantSegment.FOOD_SERVICE, null)));
    }

    @Test
    void contributesToIndex_failsOpenWhenClassifierDisabled() {
        when(cnpjActivityClient.isEnabled()).thenReturn(false);

        assertTrue(gate.contributesToIndex(market(MerchantSegment.UNKNOWN, null)),
                "dev environments without CNAE lookup must not starve the index");
        assertTrue(gate.contributesToIndex(null));
        assertFalse(gate.contributesToIndex(market(MerchantSegment.OTHER, null)),
                "a genuinely classified grey merchant still waits for review");
    }

    @Test
    void supportedStatusNeverTouchesTheClassifierFlag() {
        gate.statusOf(market(MerchantSegment.SUPERMARKET, null));
        verifyNoInteractions(cnpjActivityClient);
    }
}
