package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.model.ReceiptItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns receipt lines into {@link PurchasedLine}s. The price paid for a line is, in order: the
 * manual promo price the user typed; else the as-printed total minus the line's share of the
 * receipt's discount (the NFC-e prints one discount for the whole receipt, shared here in
 * proportion to each line's total). A receipt with manual prices never also shares its discount —
 * the user already told us where it went.
 */
final class PurchasedLineFactory {

    private static final int UNIT_PRICE_SCALE = 4;
    private static final int SHARE_SCALE = 10;

    private PurchasedLineFactory() {}

    /** {@code receiptLines} must hold EVERY non-excluded line of each receipt, comparable or not. */
    static List<PurchasedLine> comparableLinesOf(List<ReceiptItem> receiptLines) {
        return receiptLines.stream()
                .collect(Collectors.groupingBy(line -> line.getReceipt().getId()))
                .values().stream()
                .flatMap(linesOfOneReceipt -> comparableLinesOfReceipt(linesOfOneReceipt).stream())
                .toList();
    }

    private static List<PurchasedLine> comparableLinesOfReceipt(List<ReceiptItem> linesOfOneReceipt) {
        var paidFraction = fractionPaidAfterReceiptDiscount(linesOfOneReceipt);
        return linesOfOneReceipt.stream()
                .filter(PurchasedLineFactory::isComparable)
                .map(line -> toPurchasedLine(line, paidFraction))
                .toList();
    }

    /** 1 when nothing is shared: no receipt discount, or the user typed promo prices by hand. */
    private static BigDecimal fractionPaidAfterReceiptDiscount(List<ReceiptItem> linesOfOneReceipt) {
        var hasManualPrices = linesOfOneReceipt.stream().anyMatch(line -> line.getPaidTotalPrice() != null);
        var discount = linesOfOneReceipt.get(0).getReceipt().getDiscountTotal();
        var gross = linesOfOneReceipt.stream()
                .map(ReceiptItem::getTotalPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (hasManualPrices || discount == null || discount.signum() <= 0 || discount.compareTo(gross) >= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.subtract(discount.divide(gross, SHARE_SCALE, RoundingMode.HALF_UP));
    }

    private static boolean isComparable(ReceiptItem line) {
        return line.getProduct() != null
                && !line.isExcludedFromPersonal()
                && line.getReceipt().getIssuedAt() != null
                && line.getQuantity() != null && line.getQuantity().signum() > 0
                && line.getTotalPrice() != null && line.getTotalPrice().signum() > 0;
    }

    private static PurchasedLine toPurchasedLine(ReceiptItem line, BigDecimal paidFraction) {
        var receipt = line.getReceipt();
        var paidTotal = line.getPaidTotalPrice() != null
                ? line.getPaidTotalPrice()
                : line.getTotalPrice().multiply(paidFraction).setScale(2, RoundingMode.HALF_UP);
        var paidUnitPrice = paidTotal.divide(line.getQuantity(), UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
        return new PurchasedLine(line.getProduct(), receipt.getCnpjEmitente(), receipt.getMarketName(),
                receipt.getIssuedAt(), line.getQuantity(), paidTotal, paidUnitPrice);
    }
}
