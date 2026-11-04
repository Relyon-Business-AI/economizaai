package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.InvalidMerchantSegmentException;
import com.relyon.economizaai.exception.MarketNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMerchantServiceTest {

    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private PriceIndexService priceIndexService;

    @InjectMocks private AdminMerchantService service;

    private MarketLocation greyMarket(String cnpj, String name) {
        return MarketLocation.builder()
                .cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8))
                .name(name).segment(MerchantSegment.OTHER)
                .build();
    }

    private Receipt confirmedReceipt(String cnpj) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
        return Receipt.builder()
                .id(UUID.randomUUID()).user(user).household(household)
                .cnpjEmitente(cnpj).status(ReceiptStatus.CONFIRMED)
                .build();
    }

    @Test
    void listGreyMerchants_ranksByReceiptCountDescending() {
        var quietMarket = greyMarket("11111111000111", "Padaria Quieta");
        var busyMarket = greyMarket("22222222000122", "Padaria Movimentada");
        when(marketLocationRepository.findAllBySupportOverrideIsNullAndSegmentIn(any()))
                .thenReturn(List.of(quietMarket, busyMarket));
        when(receiptRepository.countByCnpjEmitente("11111111000111")).thenReturn(1L);
        when(receiptRepository.countByCnpjEmitente("22222222000122")).thenReturn(7L);

        var queue = service.listGreyMerchants();

        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).cnpj()).isEqualTo("22222222000122");
        assertThat(queue.get(0).receiptCount()).isEqualTo(7L);
    }

    @Test
    void setSupportOverride_unknownCnpj_throws() {
        when(marketLocationRepository.findByCnpj("99999999000199")).thenReturn(Optional.empty());

        assertThrows(MarketNotFoundException.class,
                () -> service.setSupportOverride("99999999000199", MerchantSupportOverride.BLOCKED));
    }

    @Test
    void setSupportOverride_blocked_doesNotBackfill() {
        var market = greyMarket("11111111000111", "Bar Disfarçado");
        when(marketLocationRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));

        var result = service.setSupportOverride("11111111000111", MerchantSupportOverride.BLOCKED);

        assertThat(market.getSupportOverride()).isEqualTo(MerchantSupportOverride.BLOCKED);
        assertThat(result.backfilledReceipts()).isZero();
        verify(priceIndexService, never()).recordContributions(any());
    }

    @Test
    void setSupportOverride_supported_backfillsOnlyReceiptsWithoutPriorContribution() {
        var market = greyMarket("11111111000111", "Padaria do Bairro");
        var freshReceipt = confirmedReceipt("11111111000111");
        var alreadyContributed = confirmedReceipt("11111111000111");
        when(marketLocationRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));
        when(receiptRepository.findAllByCnpjEmitenteAndStatus("11111111000111", ReceiptStatus.CONFIRMED))
                .thenReturn(List.of(freshReceipt, alreadyContributed));
        when(auditRepository.existsByReceiptId(freshReceipt.getId())).thenReturn(false);
        when(auditRepository.existsByReceiptId(alreadyContributed.getId())).thenReturn(true);
        when(priceIndexService.recordContributions(freshReceipt)).thenReturn(3);

        var result = service.setSupportOverride("11111111000111", MerchantSupportOverride.SUPPORTED);

        assertThat(market.getSupportOverride()).isEqualTo(MerchantSupportOverride.SUPPORTED);
        assertThat(result.backfilledReceipts()).isEqualTo(1);
        verify(priceIndexService, never()).recordContributions(alreadyContributed);
    }

    @Test
    void classifyMerchant_market_setsSegmentClearsOverridePinsAndBackfills() {
        var market = greyMarket("17302829000494", "JL SUPERMERCADOS");
        var receipt = confirmedReceipt("17302829000494");
        when(marketLocationRepository.findByCnpj("17302829000494")).thenReturn(Optional.of(market));
        when(receiptRepository.findAllByCnpjEmitenteAndStatus("17302829000494", ReceiptStatus.CONFIRMED))
                .thenReturn(List.of(receipt));
        when(auditRepository.existsByReceiptId(receipt.getId())).thenReturn(false);
        when(priceIndexService.recordContributions(receipt)).thenReturn(2);

        var result = service.classifyMerchant("17302829000494", MerchantSegment.SUPERMARKET);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.SUPERMARKET);
        assertThat(market.getSupportOverride()).isNull(); // segment drives — no SUPPORTED override leak
        assertThat(market.getSegmentAttempts()).isEqualTo(MarketLocationService.MANUAL_SEGMENT_ATTEMPTS);
        assertThat(market.getSegmentClassifiedAt()).isNotNull();
        assertThat(result.segment()).isEqualTo(MerchantSegment.SUPERMARKET);
        assertThat(result.backfilledReceipts()).isEqualTo(1);
    }

    @Test
    void classifyMerchant_pharmacy_backfills() {
        var market = greyMarket("11111111000111", "Drogaria Cinza");
        when(marketLocationRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));
        when(receiptRepository.findAllByCnpjEmitenteAndStatus("11111111000111", ReceiptStatus.CONFIRMED))
                .thenReturn(List.of());

        var result = service.classifyMerchant("11111111000111", MerchantSegment.PHARMACY);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.PHARMACY);
        assertThat(market.getSupportOverride()).isNull();
        assertThat(result.backfilledReceipts()).isZero();
    }

    @Test
    void classifyMerchant_fuel_setsSegmentWithoutBackfill() {
        var market = greyMarket("11111111000111", "Posto Cinza");
        when(marketLocationRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));

        var result = service.classifyMerchant("11111111000111", MerchantSegment.GAS_STATION);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.GAS_STATION);
        assertThat(market.getSupportOverride()).isNull(); // combustível fora do índice de mercado
        assertThat(result.backfilledReceipts()).isZero();
        verify(priceIndexService, never()).recordContributions(any());
    }

    @Test
    void classifyMerchant_other_blocksWithoutBackfill() {
        var market = greyMarket("11111111000111", "Restaurante Disfarçado");
        when(marketLocationRepository.findByCnpj("11111111000111")).thenReturn(Optional.of(market));

        var result = service.classifyMerchant("11111111000111", MerchantSegment.OTHER);

        assertThat(market.getSegment()).isEqualTo(MerchantSegment.OTHER);
        assertThat(market.getSupportOverride()).isEqualTo(MerchantSupportOverride.BLOCKED);
        assertThat(result.backfilledReceipts()).isZero();
        verify(priceIndexService, never()).recordContributions(any());
    }

    @Test
    void classifyMerchant_unknownSegment_throws() {
        assertThrows(InvalidMerchantSegmentException.class,
                () -> service.classifyMerchant("11111111000111", MerchantSegment.UNKNOWN));
    }
}
