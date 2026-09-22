package com.relyon.economizaai.service.report;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptSpecifications;
import com.relyon.economizaai.service.report.PurchaseReportData.CategorySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.ItemRow;
import com.relyon.economizaai.service.report.PurchaseReportData.Kpis;
import com.relyon.economizaai.service.report.PurchaseReportData.MarketSpend;
import com.relyon.economizaai.service.report.PurchaseReportData.MonthlySpend;
import com.relyon.economizaai.service.report.PurchaseReportData.ProductSpend;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Builds {@link PurchaseReportData} from the household's CONFIRMED receipts:
 * one pass over the rows produces the KPIs, the monthly/category/market
 * aggregates behind the charts, and the flat item table. Excluded items are
 * left out everywhere (the user said "I didn't buy this").
 */
@Service
@RequiredArgsConstructor
public class PurchaseReportAssembler {

    private static final int TOP_MARKETS = 8;
    private static final int TOP_PRODUCTS = 10;

    private final ReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public PurchaseReportData assemble(User user, LocalDateTime from, LocalDateTime to) {
        var spec = ReceiptSpecifications.forSearch(user.getHousehold().getId(), from, to,
                null, null, ReceiptStatus.CONFIRMED, null, true, null);
        var receipts = receiptRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "issuedAt"));
        var items = collectItemRows(receipts);
        return new PurchaseReportData(from, to,
                kpis(receipts, items),
                monthlySeries(items),
                categoryBreakdown(items),
                topMarkets(receipts),
                topProducts(items),
                items);
    }

    private static List<ItemRow> collectItemRows(List<Receipt> receipts) {
        var rows = new ArrayList<ItemRow>();
        for (var receipt : receipts) {
            for (var item : receipt.getItems()) {
                if (item.isExcluded() || item.isExcludedFromPersonal()) continue;
                rows.add(toRow(receipt, item));
            }
        }
        return rows;
    }

    private static ItemRow toRow(Receipt receipt, ReceiptItem item) {
        var description = item.getFriendlyDescription() != null && !item.getFriendlyDescription().isBlank()
                ? item.getFriendlyDescription() : item.getRawDescription();
        var category = item.getCategoryAtConfirmation() != null ? item.getCategoryAtConfirmation().name() : "";
        return new ItemRow(receipt.getIssuedAt(), receipt.getMarketName(), receipt.getCnpjEmitente(),
                receipt.getChaveAcesso(), description, item.getQuantity(), item.getUnit(),
                item.getUnitPrice(), item.getTotalPrice(), category, receipt.getTotalAmount());
    }

    private static Kpis kpis(List<Receipt> receipts, List<ItemRow> items) {
        var totalSpent = receipts.stream()
                .map(Receipt::getTotalAmount)
                .filter(total -> total != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var averageTicket = receipts.isEmpty()
                ? BigDecimal.ZERO
                : totalSpent.divide(BigDecimal.valueOf(receipts.size()), 2, RoundingMode.HALF_UP);
        return new Kpis(totalSpent, receipts.size(), items.size(), averageTicket);
    }

    private static List<MonthlySpend> monthlySeries(List<ItemRow> items) {
        var byMonth = new TreeMap<YearMonth, BigDecimal>();
        for (var item : items) {
            if (item.issuedAt() == null || item.itemTotal() == null) continue;
            byMonth.merge(YearMonth.from(item.issuedAt()), item.itemTotal(), BigDecimal::add);
        }
        return byMonth.entrySet().stream()
                .map(entry -> new MonthlySpend(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<CategorySpend> categoryBreakdown(List<ItemRow> items) {
        var byCategory = new LinkedHashMap<String, BigDecimal>();
        for (var item : items) {
            if (item.itemTotal() == null) continue;
            var category = item.category().isBlank() ? "OTHER" : item.category();
            byCategory.merge(category, item.itemTotal(), BigDecimal::add);
        }
        return byCategory.entrySet().stream()
                .map(entry -> new CategorySpend(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CategorySpend::total).reversed())
                .toList();
    }

    private static List<MarketSpend> topMarkets(List<Receipt> receipts) {
        var byCnpj = new LinkedHashMap<String, MarketSpend>();
        for (var receipt : receipts) {
            if (receipt.getTotalAmount() == null) continue;
            var key = receipt.getCnpjEmitente() != null ? receipt.getCnpjEmitente() : String.valueOf(receipt.getMarketName());
            byCnpj.merge(key,
                    new MarketSpend(receipt.getMarketName(), receipt.getCnpjEmitente(), receipt.getTotalAmount(), 1),
                    (existing, addition) -> new MarketSpend(existing.marketName(), existing.marketCnpj(),
                            existing.total().add(addition.total()), existing.receiptCount() + 1));
        }
        return byCnpj.values().stream()
                .sorted(Comparator.comparing(MarketSpend::total).reversed())
                .limit(TOP_MARKETS)
                .toList();
    }

    private static List<ProductSpend> topProducts(List<ItemRow> items) {
        var byDescription = new LinkedHashMap<String, ProductSpend>();
        for (var item : items) {
            if (item.itemTotal() == null || item.item() == null) continue;
            byDescription.merge(item.item(),
                    new ProductSpend(item.item(), item.itemTotal(),
                            item.quantity() == null ? BigDecimal.ZERO : item.quantity()),
                    (existing, addition) -> new ProductSpend(existing.description(),
                            existing.totalSpent().add(addition.totalSpent()),
                            existing.quantity().add(addition.quantity())));
        }
        return byDescription.values().stream()
                .sorted(Comparator.comparing(ProductSpend::totalSpent).reversed())
                .limit(TOP_PRODUCTS)
                .toList();
    }

}
