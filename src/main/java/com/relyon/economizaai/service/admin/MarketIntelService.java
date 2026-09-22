package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.MarketIntelResponse;
import com.relyon.economizaai.dto.response.MarketIntelResponse.CategorySpend;
import com.relyon.economizaai.dto.response.MarketIntelResponse.TopMarket;
import com.relyon.economizaai.dto.response.MarketIntelResponse.TopProduct;
import com.relyon.economizaai.dto.response.MarketIntelResponse.UfRow;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only market intelligence for the admin: the collaborative index size plus
 * the top products/markets, category spend and UF spread aggregated across ALL
 * households. Confirmed, non-excluded data only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIntelService {

    private static final int TOP_N = 15;

    private final ReceiptItemRepository receiptItemRepository;
    private final ReceiptRepository receiptRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final PriceObservationAuditRepository priceObservationAuditRepository;

    @Transactional(readOnly = true)
    public MarketIntelResponse report(boolean includeInternal) {
        // Index size stays global (anonymized asset); the receipt-derived top lists honor the filter.
        var observations = priceObservationRepository.count();
        var contributingHouseholds = priceObservationAuditRepository.countDistinctContributingHouseholds();

        var topProducts = new ArrayList<TopProduct>();
        for (var row : receiptItemRepository.topProductsByScans(PageRequest.of(0, TOP_N), includeInternal)) {
            topProducts.add(new TopProduct(String.valueOf(row[0]),
                    row[1] == null ? "—" : row[1].toString(), toLong(row[2])));
        }

        var topMarkets = new ArrayList<TopMarket>();
        for (var row : receiptRepository.topMarketsByScans(PageRequest.of(0, TOP_N), includeInternal)) {
            topMarkets.add(new TopMarket((String) row[0],
                    row[1] == null ? "—" : row[1].toString(), toLong(row[2]), scale((BigDecimal) row[3])));
        }

        var topMarketsByChain = new ArrayList<TopMarket>();
        for (var row : receiptRepository.topMarketsByChainScans(PageRequest.of(0, TOP_N), includeInternal)) {
            topMarketsByChain.add(new TopMarket((String) row[0],
                    row[1] == null ? "—" : row[1].toString(), toLong(row[2]), scale((BigDecimal) row[3])));
        }

        var categorySpend = new ArrayList<CategorySpend>();
        for (var row : receiptItemRepository.categorySpendGlobal(includeInternal)) {
            var category = row[0] == null ? "UNCATEGORIZED" : ((ProductCategory) row[0]).name();
            categorySpend.add(new CategorySpend(category, scale((BigDecimal) row[1]), toLong(row[2])));
        }
        categorySpend.sort(Comparator.comparing(CategorySpend::spend).reversed());

        var byUf = new ArrayList<UfRow>();
        for (var row : receiptRepository.confirmedReceiptsByUf(includeInternal)) {
            var uf = row[0] == null ? "??" : ((UnidadeFederativa) row[0]).name();
            byUf.add(new UfRow(uf, toLong(row[1]), scale((BigDecimal) row[2])));
        }

        log.info("admin.market_intel internal={} observations={} households={} topProducts={} topMarkets={} ufs={}",
                includeInternal, observations, contributingHouseholds, topProducts.size(), topMarkets.size(), byUf.size());
        return new MarketIntelResponse(observations, contributingHouseholds,
                List.copyOf(topProducts), List.copyOf(topMarkets), List.copyOf(topMarketsByChain),
                List.copyOf(categorySpend), List.copyOf(byUf));
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
