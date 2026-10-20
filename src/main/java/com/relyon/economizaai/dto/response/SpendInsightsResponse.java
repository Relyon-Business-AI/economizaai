package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SpendInsightsResponse(
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal total,
        BigDecimal totalDiscount,
        List<MonthBucket> byMonth,
        List<WeekBucket> byWeek,
        List<MarketBucket> byMarket,
        List<CategoryBucket> byCategory
) {
    public record MonthBucket(int year, int month, BigDecimal total, BigDecimal discount, long receiptCount) {}
    public record WeekBucket(int year, int week, BigDecimal total, BigDecimal discount, long receiptCount) {}
    public record MarketBucket(String cnpj, String marketName, String marketFriendlyName,
                               BigDecimal total, BigDecimal discount, long receiptCount) {
        /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
        public MarketBucket withMarketFriendlyName(String marketFriendlyName) {
            return new MarketBucket(cnpj, marketName, marketFriendlyName, total, discount, receiptCount);
        }
    }
    /**
     * Spend grouped into one category bucket.
     *
     * <p>In the GLOBAL lens {@code category} is the global enum and {@code label}
     * equals its name. In the HOUSEHOLD lens the bucket is the household's
     * EFFECTIVE category: for a custom category {@code category} is null and
     * {@code label} carries the custom name; for a (possibly corrected) enum
     * {@code category} is that enum and {@code label} is its name. {@code label}
     * is therefore the always-present, display-safe key.
     */
    /**
     * {@code parentSlug}/{@code icon}/{@code color} are populated only for household
     * CUSTOM categories (so the FE can nest them under their group and render their
     * chosen visual). Taxonomy nodes leave them null — the FE derives the group from
     * {@code slug} via its static tree.
     */
    public record CategoryBucket(ProductCategory category, String slug, String label,
                                 String parentSlug, String icon, String color,
                                 BigDecimal total, long itemCount) {
        /** Convenience for the global lens, where the label is just the enum name. */
        public static CategoryBucket ofEnum(ProductCategory category, BigDecimal total, long itemCount) {
            return new CategoryBucket(category, null, category != null ? category.name() : null,
                    null, null, null, total, itemCount);
        }

        /**
         * New taxonomy node bucket: {@code slug} drives the FE icon, {@code label} the text.
         * {@code legacyCategory} = enum derivado da categoria do nó — SÓ pra compatibilidade com
         * bundles ANTIGOS do app (pré-taxonomia), que renderizam por {@code category} e mostravam
         * tudo como "Outros" quando ele vinha nulo. Clientes novos usam slug/label e o ignoram.
         */
        public static CategoryBucket ofNode(String slug, String label, ProductCategory legacyCategory,
                                            BigDecimal total, long itemCount) {
            return new CategoryBucket(legacyCategory, slug, label, null, null, null, total, itemCount);
        }

        /** Household custom category bucket: carries its visual + the group it nests under. */
        public static CategoryBucket ofCustom(String key, String label, String parentSlug,
                                              String icon, String color, BigDecimal total, long itemCount) {
            return new CategoryBucket(null, key, label, parentSlug, icon, color, total, itemCount);
        }
    }
}
