package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasedLineFactoryTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 10, 18, 30);

    private final Product rice = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
    private final Product beans = Product.builder().id(UUID.randomUUID()).normalizedName("Feijao").build();

    @Test
    void withoutDiscount_theLineCostsItsPrintedTotal() {
        var receipt = receipt(null);

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(line(receipt, rice, "2", "20.00")));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).paidTotal()).isEqualByComparingTo("20.00");
        assertThat(lines.get(0).paidUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(lines.get(0).purchasedAt()).isEqualTo(ISSUED_AT);
        assertThat(lines.get(0).marketCnpj()).isEqualTo("12345678000190");
    }

    @Test
    void receiptDiscount_isSharedInProportionToEachLineTotal() {
        var receipt = receipt("10.00");

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(
                line(receipt, rice, "1", "60.00"),
                line(receipt, beans, "1", "40.00")));

        assertThat(lines).extracting(PurchasedLine::paidTotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("54.00"), new BigDecimal("36.00"));
    }

    @Test
    void receiptDiscount_alsoCountsTheLinesWithoutProductInTheShare() {
        var receipt = receipt("10.00");

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(
                line(receipt, rice, "1", "50.00"),
                line(receipt, null, "1", "50.00")));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).paidTotal()).isEqualByComparingTo("45.00");
    }

    @Test
    void manualPromoPrice_winsAndTheReceiptDiscountIsNotSharedOnTop() {
        var receipt = receipt("10.00");
        var promoLine = line(receipt, rice, "2", "60.00");
        promoLine.setPaidTotalPrice(new BigDecimal("50.00"));

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(promoLine, line(receipt, beans, "1", "40.00")));

        assertThat(lines).extracting(PurchasedLine::paidTotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("40.00"));
    }

    @Test
    void discountAsLargeAsTheReceipt_isIgnored() {
        var receipt = receipt("20.00");

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(line(receipt, rice, "1", "20.00")));

        assertThat(lines.get(0).paidTotal()).isEqualByComparingTo("20.00");
    }

    @Test
    void linesThatCannotBeCompared_areLeftOut() {
        var receipt = receipt(null);
        var notMine = line(receipt, rice, "1", "10.00");
        notMine.setExcludedFromPersonal(true);

        var lines = PurchasedLineFactory.comparableLinesOf(List.of(
                notMine,
                line(receipt, null, "1", "10.00"),
                line(receipt, rice, "0", "10.00"),
                line(receipt, rice, "1", "0.00")));

        assertThat(lines).isEmpty();
    }

    private Receipt receipt(String discountTotal) {
        return Receipt.builder()
                .id(UUID.randomUUID())
                .cnpjEmitente("12345678000190").marketName("MERCADO X LTDA")
                .issuedAt(ISSUED_AT)
                .discountTotal(discountTotal == null ? null : new BigDecimal(discountTotal))
                .build();
    }

    private ReceiptItem line(Receipt receipt, Product product, String quantity, String totalPrice) {
        return ReceiptItem.builder()
                .receipt(receipt).product(product).lineNumber(1).rawDescription("item")
                .quantity(new BigDecimal(quantity)).totalPrice(new BigDecimal(totalPrice))
                .build();
    }
}
