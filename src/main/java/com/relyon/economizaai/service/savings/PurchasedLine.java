package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.model.Product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One line the household bought, at the price it really paid: the as-printed total minus the
 * line's manual promo price or its share of the receipt's discount.
 */
record PurchasedLine(Product product, String marketCnpj, String marketName, LocalDateTime purchasedAt,
                     BigDecimal quantity, BigDecimal paidTotal, BigDecimal paidUnitPrice) {

    LocalDate day() {
        return purchasedAt.toLocalDate();
    }
}
