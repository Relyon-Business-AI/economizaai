package com.relyon.economizai.service.ecommerce;

import com.relyon.economizai.config.EcommerceProperties;
import com.relyon.economizai.model.EcommerceOffer;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.EcommerceOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommerceOfferServiceTest {

    @Mock private EcommerceOfferRepository offerRepository;
    private EcommerceProperties properties;
    private EcommerceOfferService service;

    @BeforeEach
    void setUp() {
        properties = new EcommerceProperties();
        properties.setWorthItMinSavings(BigDecimal.ZERO);
        // No providers configured — curated-only path (the MVP default).
        service = new EcommerceOfferService(offerRepository, properties, List.of());
    }

    private ReceiptItem item(String ean, String paidUnitPrice) {
        return ReceiptItem.builder().ean(ean)
                .paidUnitPrice(paidUnitPrice == null ? null : new BigDecimal(paidUnitPrice))
                .unitPrice(new BigDecimal("99.99")).build();
    }

    private EcommerceOffer curated(String ean, String price, String freight) {
        return EcommerceOffer.builder().ean(ean).provider("mercadolivre").title("Café online")
                .price(new BigDecimal(price)).freight(freight == null ? null : new BigDecimal(freight))
                .currency("BRL").inStock(true).active(true).curated(true).build();
    }

    @Test
    void returnsEmptyWhenItemHasNoEan() {
        var result = service.bestOfferForItem(item(null, "20.00"), null);
        assertThat(result).isEmpty();
    }

    @Test
    void returnsCuratedOfferAndFlagsWorthItWhenCheaperThanPaid() {
        when(offerRepository.findByEanAndActiveTrue("789")).thenReturn(List.of(curated("789", "15.00", "0.00")));

        var result = service.bestOfferForItem(item("789", "20.00"), null);

        assertThat(result).isPresent();
        var offer = result.get();
        assertThat(offer.total()).isEqualByComparingTo("15.00");
        assertThat(offer.worthIt()).isTrue();
        assertThat(offer.savings()).isEqualByComparingTo("5.00");
        assertThat(offer.curated()).isTrue();
    }

    @Test
    void notWorthItWhenOnlineIsMoreExpensive() {
        when(offerRepository.findByEanAndActiveTrue("789")).thenReturn(List.of(curated("789", "15.00", "3.00")));

        var offer = service.bestOfferForItem(item("789", "10.00"), null).orElseThrow();

        assertThat(offer.total()).isEqualByComparingTo("18.00");
        assertThat(offer.worthIt()).isFalse();
        assertThat(offer.savings()).isEqualByComparingTo("-8.00");
    }

    @Test
    void picksCheapestTotalAcrossOffers() {
        when(offerRepository.findByEanAndActiveTrue("789")).thenReturn(List.of(
                curated("789", "20.00", "0.00"),
                curated("789", "9.00", "3.00"),   // total 12
                curated("789", "10.00", "0.00")));  // total 10 (cheapest)

        var offer = service.bestOfferForItem(item("789", "25.00"), null).orElseThrow();

        assertThat(offer.total()).isEqualByComparingTo("10.00");
    }

    @Test
    void respectsWorthItMinSavingsThreshold() {
        properties.setWorthItMinSavings(new BigDecimal("6.00"));
        when(offerRepository.findByEanAndActiveTrue("789")).thenReturn(List.of(curated("789", "15.00", "0.00")));

        var offer = service.bestOfferForItem(item("789", "20.00"), null).orElseThrow();

        // Saves R$5 but threshold is R$6 → not flagged worth it.
        assertThat(offer.savings()).isEqualByComparingTo("5.00");
        assertThat(offer.worthIt()).isFalse();
    }

    @Test
    void fallsBackToProductEanWhenItemEanMissing() {
        var product = Product.builder().ean("555").normalizedName("Cafe").build();
        var receiptItem = ReceiptItem.builder().product(product).paidUnitPrice(new BigDecimal("20.00"))
                .unitPrice(new BigDecimal("20.00")).build();
        lenient().when(offerRepository.findByEanAndActiveTrue("555"))
                .thenReturn(List.of(curated("555", "12.00", "0.00")));

        var offer = service.bestOfferForItem(receiptItem, null).orElseThrow();

        assertThat(offer.total()).isEqualByComparingTo("12.00");
    }
}
