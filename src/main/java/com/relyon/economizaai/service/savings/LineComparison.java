package com.relyon.economizaai.service.savings;

import com.relyon.economizaai.service.savings.ReferencePriceBook.Source;

import java.math.BigDecimal;

/** A purchased line next to its reference: {@code difference} is reference minus paid, for the whole line, in R$. */
record LineComparison(PurchasedLine line, Source source, BigDecimal difference) {

    BigDecimal saved() {
        return difference.max(BigDecimal.ZERO);
    }

    BigDecimal paidAboveReference() {
        return difference.negate().max(BigDecimal.ZERO);
    }
}
