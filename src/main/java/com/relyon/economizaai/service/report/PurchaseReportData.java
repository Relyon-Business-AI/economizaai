package com.relyon.economizaai.service.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Everything the purchase report renderers need, assembled once from the
 * household's CONFIRMED receipts in the requested window: headline KPIs,
 * the aggregate series behind the charts, and the flat item rows.
 */
public record PurchaseReportData(
        LocalDateTime from,
        LocalDateTime to,
        Kpis kpis,
        List<MonthlySpend> monthlySeries,
        List<CategorySpend> categoryBreakdown,
        List<MarketSpend> topMarkets,
        List<ProductSpend> topProducts,
        List<ItemRow> items) {

    public record Kpis(BigDecimal totalSpent, int receiptCount, int itemCount, BigDecimal averageTicket) {}

    public record MonthlySpend(YearMonth month, BigDecimal total) {}

    /** {@code category} is the ProductCategory enum name (renderers localize it). */
    public record CategorySpend(String category, BigDecimal total) {}

    public record MarketSpend(String marketName, String marketCnpj, BigDecimal total, long receiptCount) {}

    public record ProductSpend(String description, BigDecimal totalSpent, BigDecimal quantity) {}

    public record ItemRow(LocalDateTime issuedAt, String market, String marketCnpj, String chaveAcesso,
                          String item, BigDecimal quantity, String unit, BigDecimal unitPrice,
                          BigDecimal itemTotal, String category, BigDecimal receiptTotal) {}
}
