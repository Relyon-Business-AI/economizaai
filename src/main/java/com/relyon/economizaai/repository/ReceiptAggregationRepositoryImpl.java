package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Receipt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

public class ReceiptAggregationRepositoryImpl implements ReceiptAggregationRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public BigDecimal sumTotalAmount(Specification<Receipt> spec) {
        var criteriaBuilder = entityManager.getCriteriaBuilder();
        var query = criteriaBuilder.createQuery(BigDecimal.class);
        var root = query.from(Receipt.class);
        query.select(criteriaBuilder.coalesce(criteriaBuilder.sum(root.get("totalAmount")), BigDecimal.ZERO));
        if (spec != null) {
            var predicate = spec.toPredicate(root, query, criteriaBuilder);
            if (predicate != null) query.where(predicate);
        }
        return entityManager.createQuery(query).getSingleResult();
    }
}
