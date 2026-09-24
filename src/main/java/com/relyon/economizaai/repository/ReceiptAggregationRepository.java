package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Receipt;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * Aggregations over the receipt corpus that {@link JpaSpecificationExecutor} can't
 * express (it does count/find, not sum). Used by the admin Notas list to show the
 * total value of the matching notes — the same filters the paginated list applies.
 */
public interface ReceiptAggregationRepository {

    /** Sum of {@code totalAmount} over every receipt matching {@code spec} (0 when none). */
    BigDecimal sumTotalAmount(Specification<Receipt> spec);
}
