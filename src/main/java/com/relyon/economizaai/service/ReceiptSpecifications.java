package com.relyon.economizaai.service;

import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds JPA Specifications for receipt searches. Centralized so both the
 * household-scoped user view ({@link ReceiptService#list}) and the
 * admin-scoped cross-household view share the same predicate logic for
 * date / market / category / content-search filters.
 */
public final class ReceiptSpecifications {

    private ReceiptSpecifications() {}

    /**
     * @param householdId when non-null, restricts to receipts in that household.
     *                    Pass null for cross-household admin queries.
     * @param hideFailedParse true for the user-facing list (failed-parse rows
     *                        are noise); false for admin (you want to see them).
     */
    public static Specification<Receipt> forSearch(UUID householdId,
                                                   LocalDateTime from,
                                                   LocalDateTime to,
                                                   String cnpj,
                                                   List<ProductCategory> categories,
                                                   ReceiptStatus status,
                                                   String search,
                                                   boolean hideFailedParse,
                                                   UnidadeFederativa uf) {
        return forSearch(householdId, from, to, cnpj, categories, status, search, hideFailedParse, uf, null);
    }

    /**
     * @param parseErrorReason when non-null, restricts to receipts whose
     *        {@code parseErrorReason} starts with this machine key — lets the ops
     *        "erros mais comuns" list drill into the receipts behind one error.
     */
    public static Specification<Receipt> forSearch(UUID householdId,
                                                   LocalDateTime from,
                                                   LocalDateTime to,
                                                   String cnpj,
                                                   List<ProductCategory> categories,
                                                   ReceiptStatus status,
                                                   String search,
                                                   boolean hideFailedParse,
                                                   UnidadeFederativa uf,
                                                   String parseErrorReason) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (householdId != null) {
                predicates.add(cb.equal(root.get("household").get("id"), householdId));
            }
            if (uf != null) {
                predicates.add(cb.equal(root.get("uf"), uf));
            }
            if (parseErrorReason != null && !parseErrorReason.isBlank()) {
                // The error breakdown groups by the machine key before ':' — match that exact
                // key (bare) or "key:details", without over-matching a longer sibling key.
                predicates.add(cb.or(
                        cb.equal(root.get("parseErrorReason"), parseErrorReason),
                        cb.like(root.get("parseErrorReason"), parseErrorReason + ":%")));
            }
            // FAILED_PARSE rows are kept for ops review (PRO-43) but hidden from
            // the user history — the user didn't actually buy anything from a
            // failed scan, so it would just be noise in their "compras" list.
            if (hideFailedParse) {
                predicates.add(cb.notEqual(root.get("status"), ReceiptStatus.FAILED_PARSE));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("issuedAt"), to));
            if (cnpj != null) predicates.add(cb.equal(root.get("cnpjEmitente"), cnpj));
            // category + search both require joining items, so do it once and
            // reuse — apply distinct, since a receipt can have many matching items.
            var hasCategories = categories != null && !categories.isEmpty();
            if (hasCategories || search != null) {
                if (query != null) query.distinct(true);
                var items = root.join("items", JoinType.INNER);
                if (hasCategories) {
                    var product = items.join("product", JoinType.INNER);
                    predicates.add(product.get("category").in(categories));
                }
                if (search != null) {
                    var like = "%" + search.toLowerCase() + "%";
                    var productLeft = items.join("product", JoinType.LEFT);
                    var searchMatches = new ArrayList<Predicate>(List.of(
                            cb.like(cb.lower(items.get("rawDescription")), like),
                            cb.like(cb.lower(items.get("friendlyDescription")), like),
                            cb.like(cb.lower(productLeft.get("normalizedName")), like),
                            cb.like(cb.lower(root.get("marketName")), like)
                    ));
                    // The household's product rename (alias) must also match — a
                    // user who renamed "ARROZ TIO JOAO 5KG" to "arroz" expects
                    // searching "arroz" to find those receipts.
                    if (query != null) {
                        var aliasSubquery = query.subquery(Integer.class);
                        var alias = aliasSubquery.from(HouseholdProductAlias.class);
                        aliasSubquery.select(cb.literal(1)).where(
                                cb.equal(alias.get("product"), items.get("product")),
                                cb.equal(alias.get("household"), root.get("household")),
                                cb.like(cb.lower(alias.get("friendlyName")), like));
                        searchMatches.add(cb.exists(aliasSubquery));
                    }
                    predicates.add(cb.or(searchMatches.toArray(new Predicate[0])));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
