package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.MarketIntelResponse;
import com.relyon.economizaai.dto.response.MarketIntelResponse.CategorySpend;
import com.relyon.economizaai.dto.response.MarketIntelResponse.TopMarket;
import com.relyon.economizaai.dto.response.MarketIntelResponse.TopProduct;
import com.relyon.economizaai.dto.response.MarketIntelResponse.UfRow;
import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.CategoryRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final CategoryRepository categoryRepository;

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

        var categorySpend = taxonomyCategorySpend(includeInternal);

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

    /**
     * "Gasto por categoria" na taxonomia NOVA (grupo → folha): agrega pelo nó
     * congelado na confirmação; item pré-taxonomia (nó nulo) cai no grupo default
     * do enum legado ({@code categories.legacy_enum}); sem sinal nenhum → "Sem
     * categoria". Grupos e folhas ordenados por gasto desc.
     */
    private List<CategorySpend> taxonomyCategorySpend(boolean includeInternal) {
        var nodesById = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        var groupByLegacyEnum = nodesById.values().stream()
                .filter(node -> node.getParent() == null && node.getLegacyEnum() != null)
                .collect(Collectors.toMap(Category::getLegacyEnum, Function.identity()));

        var groups = new LinkedHashMap<String, GroupAggregate>();
        for (var row : receiptItemRepository.categoryNodeSpendGlobal(includeInternal)) {
            var node = row[0] == null ? null : nodesById.get((UUID) row[0]);
            var legacyEnum = (ProductCategory) row[1];
            var spend = scale((BigDecimal) row[2]);
            var items = toLong(row[3]);

            Category group;
            Category leaf;
            if (node != null) {
                group = node.getParent() == null ? node : node.getParent();
                leaf = node.getParent() == null ? null : node;
            } else {
                group = legacyEnum == null ? null : groupByLegacyEnum.get(legacyEnum);
                leaf = null;
            }
            var groupSlug = group == null ? "sem-categoria" : group.getSlug();
            var groupName = group == null ? "Sem categoria" : group.getNamePt();
            groups.computeIfAbsent(groupSlug, slug -> new GroupAggregate(groupName)).add(spend, items, leaf);
        }
        return groups.entrySet().stream()
                .map(entry -> entry.getValue().toResponse(entry.getKey()))
                .sorted(Comparator.comparing(CategorySpend::spend).reversed())
                .toList();
    }

    /** Acumulador mutável de um grupo + suas folhas. */
    private static final class GroupAggregate {
        private final String name;
        private BigDecimal spend = BigDecimal.ZERO;
        private long items = 0;
        private final Map<String, CategorySpend> leaves = new LinkedHashMap<>();

        private GroupAggregate(String name) {
            this.name = name;
        }

        private void add(BigDecimal rowSpend, long rowItems, Category leaf) {
            spend = spend.add(rowSpend);
            items += rowItems;
            if (leaf != null) {
                leaves.merge(leaf.getSlug(),
                        new CategorySpend(leaf.getSlug(), leaf.getNamePt(), rowSpend, rowItems, List.of()),
                        (existing, added) -> new CategorySpend(existing.slug(), existing.name(),
                                existing.spend().add(added.spend()), existing.items() + added.items(), List.of()));
            }
        }

        private CategorySpend toResponse(String slug) {
            var children = leaves.values().stream()
                    .sorted(Comparator.comparing(CategorySpend::spend).reversed())
                    .toList();
            return new CategorySpend(slug, name, spend, items, children);
        }
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }
}
