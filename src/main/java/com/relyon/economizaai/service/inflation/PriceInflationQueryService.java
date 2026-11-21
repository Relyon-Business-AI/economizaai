package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.dto.response.PriceInflationResponse;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Basket;
import com.relyon.economizaai.dto.response.PriceInflationResponse.CategoryChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Community;
import com.relyon.economizaai.dto.response.PriceInflationResponse.FoodChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.Official;
import com.relyon.economizaai.dto.response.PriceInflationResponse.OfficialGroup;
import com.relyon.economizaai.dto.response.PriceInflationResponse.SeriesPoint;
import com.relyon.economizaai.dto.response.PriceInflationStateResponse;
import com.relyon.economizaai.model.OfficialInflationMonth;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PriceInflationScope;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.CommunityPriceChangeRepository;
import com.relyon.economizaai.repository.OfficialInflationMonthRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.IndexPoint;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.Span;
import com.relyon.economizaai.service.inflation.CommunityIndexReader.WindowData;
import com.relyon.economizaai.service.sefaz.StateStatusService;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers "Preços e inflação": the official food inflation and the community's price change
 * over the same months, for Brasil or a state, plus the household's own basket, the categories,
 * the foods and IBGE's food groups. Every threshold comes from the admin settings at call time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceInflationQueryService {

    private static final String NATIONAL_AREA_NAME = "Brasil";
    private static final String NO_STATE = "";

    private final PriceInflationAudienceGate audienceGate;
    private final PriceInflationProperties properties;
    private final OfficialInflationLoader officialLoader;
    private final OfficialInflationMonthRepository officialRepository;
    private final CommunityPriceChangeRepository changeRepository;
    private final CommunityIndexReader communityReader;
    private final ViewerLocationService viewerLocationService;
    private final CategoryResolver categoryResolver;

    private record OfficialArea(String code, String name, boolean nationalFallback) {}

    /** The community side of one scope: the months compared (the whole window when there is no valid month). */
    private record CommunityRead(boolean sufficient, Span span, List<IndexPoint> series) {}

    /** Per month: Σ w·ln r, Σ w and the pairs of one category or food. */
    private static final class ItemMonths {
        private final Map<YearMonth, double[]> totalsByMonth = new HashMap<>();
        private int pairCount;

        private void add(YearMonth month, double weightedLogSum, double weightSum, int pairs) {
            var totals = totalsByMonth.computeIfAbsent(month, key -> new double[2]);
            totals[0] += weightedLogSum;
            totals[1] += weightSum;
            pairCount += pairs;
        }

        private double changePercent() {
            var links = totalsByMonth.values().stream()
                    .map(totals -> CommunityIndexMath.link(totals[0], totals[1]))
                    .filter(link -> link.isPresent())
                    .map(link -> link.getAsDouble())
                    .toList();
            return CommunityIndexMath.changePercentOfLinks(links);
        }
    }

    public PriceInflationResponse overview(User viewer, PriceInflationScope requestedScope,
                                           UnidadeFederativa requestedState, Integer requestedMonths) {
        audienceGate.requireVisibleTo(viewer);
        var months = properties.offeredPeriodOrInitial(requestedMonths);
        var viewerState = viewerLocationService.majorityState(viewer);
        var state = scopeState(requestedScope, requestedState, viewerState);
        var newestOfficialMonth = officialLoader.newestNationalMonth();
        var window = windowEndingAt(newestOfficialMonth, months);
        var data = communityReader.read(window);

        var community = communityOf(data, state);
        var span = community.span();
        var official = newestOfficialMonth.isPresent() ? officialOf(areaOf(state), span) : null;
        var communitySection = communitySection(community, data, state);
        var basket = basketOf(viewer, viewerState, data, span);
        log.debug("price_inflation.overview scope={} months={} span={}..{} community={}",
                state == null ? PriceInflationScope.BR : state, months, span.from(), span.to(), communitySection.status());

        return new PriceInflationResponse(
                state == null ? PriceInflationScope.BR.name() : PriceInflationScope.STATE.name(),
                state,
                stateNameOf(state),
                viewerState,
                stateNameOf(viewerState),
                months,
                span.from().toString(),
                span.to().toString(),
                span.length() < months,
                official == null ? null : official.section(),
                communitySection,
                basket,
                difference(communitySection.changePercent(), official == null ? null : official.section().changePercent()),
                community.sufficient() ? categoriesOf(span, state) : List.of(),
                community.sufficient() ? foodsOf(span, state) : List.of(),
                official == null ? List.of() : official.groups());
    }

    public List<PriceInflationStateResponse> states(User viewer, Integer requestedMonths) {
        audienceGate.requireVisibleTo(viewer);
        var months = properties.offeredPeriodOrInitial(requestedMonths);
        var newestOfficialMonth = officialLoader.newestNationalMonth();
        var window = windowEndingAt(newestOfficialMonth, months);
        var data = communityReader.read(window);
        var foodChangesByArea = foodChangesByArea(window);
        var areaByState = areaByState();

        return Arrays.stream(UnidadeFederativa.values())
                .map(UnidadeFederativa::name)
                .sorted()
                .map(state -> stateLine(state, data, areaByState, foodChangesByArea, newestOfficialMonth.isPresent()))
                .toList();
    }

    // ---------- scope and window ----------

    private static String stateNameOf(String state) {
        return state == null ? null : StateStatusService.stateName(UnidadeFederativa.valueOf(state));
    }

    /** Null means Brasil: asked for, or a state was asked for and neither given nor known from the notas. */
    private static String scopeState(PriceInflationScope requestedScope, UnidadeFederativa requestedState,
                                     String viewerState) {
        if (requestedScope != PriceInflationScope.STATE) return null;
        return requestedState != null ? requestedState.name() : viewerState;
    }

    /** The requested months ending at the newest official month (the last closed month before IBGE was ever loaded). */
    private static Span windowEndingAt(Optional<YearMonth> newestOfficialMonth, int months) {
        var lastMonth = newestOfficialMonth.orElseGet(() -> BrazilClock.currentYearMonth().minusMonths(1));
        return new Span(lastMonth.minusMonths(months - 1L), lastMonth);
    }

    // ---------- community ----------

    private static CommunityRead communityOf(WindowData data, String state) {
        return data.comparableSpan(state)
                .map(span -> new CommunityRead(true, span, CommunityIndexMath.chain(span, data.links(state))))
                .orElseGet(() -> new CommunityRead(false, data.window(), List.of()));
    }

    private static Community communitySection(CommunityRead community, WindowData data, String state) {
        var pairCount = data.pairCount(community.span(), state);
        var storeCount = data.storeCount(community.span(), state);
        if (!community.sufficient()) {
            return new Community(PriceInflationResponse.STATUS_INSUFFICIENT, null, pairCount, storeCount, List.of());
        }
        return new Community(PriceInflationResponse.STATUS_OK,
                CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercent(community.series())),
                pairCount, storeCount, points(community.series()));
    }

    private static BigDecimal difference(BigDecimal communityChangePercent, BigDecimal officialChangePercent) {
        return communityChangePercent == null || officialChangePercent == null
                ? null : communityChangePercent.subtract(officialChangePercent);
    }

    // ---------- official ----------

    private record OfficialRead(Official section, List<OfficialGroup> groups) {}

    private OfficialArea areaOf(String state) {
        if (state == null) return new OfficialArea(OfficialInflationLoader.NATIONAL_AREA_CODE, NATIONAL_AREA_NAME, false);
        return Optional.ofNullable(areaByState().get(state))
                .orElseGet(() -> new OfficialArea(OfficialInflationLoader.NATIONAL_AREA_CODE, NATIONAL_AREA_NAME, true));
    }

    /** The IBGE area of each state that has one (the first by code, should a state ever have two). */
    private Map<String, OfficialArea> areaByState() {
        var areas = new HashMap<String, OfficialArea>();
        officialRepository.stateAreas().forEach(row ->
                areas.putIfAbsent((String) row[0], new OfficialArea((String) row[1], (String) row[2], false)));
        return areas;
    }

    private OfficialRead officialOf(OfficialArea area, Span span) {
        var foodAtHomeItem = properties.getSidra().getFoodAtHomeItem();
        var monthsByItem = officialRepository
                .findAllByAreaCodeAndReferenceMonthBetween(area.code(), span.from().atDay(1), span.to().atDay(1)).stream()
                .collect(Collectors.groupingBy(OfficialInflationMonth::getItemCode, LinkedHashMap::new, Collectors.toList()));
        var series = CommunityIndexMath.chain(span, linkByMonth(monthsByItem.getOrDefault(foodAtHomeItem, List.of())));
        var section = new Official(area.name(), area.nationalFallback(),
                CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercent(series)), points(series));
        var groups = properties.getSidra().getGroupItems().stream()
                .filter(monthsByItem::containsKey)
                .map(itemCode -> officialGroup(itemCode, monthsByItem.get(itemCode)))
                .toList();
        return new OfficialRead(section, groups);
    }

    private static OfficialGroup officialGroup(String itemCode, List<OfficialInflationMonth> months) {
        return new OfficialGroup(itemCode, withoutNumericPrefix(months.get(0).getItemName()),
                CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercentOfLinks(linkByMonth(months).values())));
    }

    private static Map<YearMonth, Double> linkByMonth(List<OfficialInflationMonth> months) {
        return months.stream().collect(Collectors.toMap(
                month -> YearMonth.from(month.getReferenceMonth()),
                month -> CommunityIndexMath.linkOfPercent(month.getChangePercent()),
                (first, second) -> first));
    }

    /** IBGE names its items "1101.Cereais, leguminosas e oleaginosas". */
    static String withoutNumericPrefix(String itemName) {
        return itemName.replaceFirst("^\\d+\\.\\s*", "");
    }

    // ---------- categories and foods ----------

    private List<CategoryChange> categoriesOf(Span span, String state) {
        var locale = LocaleContextHolder.getLocale();
        var monthsByRoot = new HashMap<UUID, ItemMonths>();
        changeRepository.monthlyTotalsByCategory(span.from().atDay(1), span.to().atDay(1), state == null, stateParam(state))
                .forEach(row -> Optional.ofNullable(categoryResolver.groupIdOf((UUID) row[1])).ifPresent(rootId ->
                        addRow(monthsByRoot.computeIfAbsent(rootId, key -> new ItemMonths()), row)));
        return monthsByRoot.entrySet().stream()
                .filter(entry -> entry.getValue().pairCount >= properties.getMinPairsPerItem())
                .map(entry -> categoryChange(entry.getKey(), entry.getValue(), locale))
                .sorted(Comparator.comparing(CategoryChange::changePercent).reversed().thenComparing(CategoryChange::name))
                .toList();
    }

    private CategoryChange categoryChange(UUID rootId, ItemMonths months, Locale locale) {
        return new CategoryChange(categoryResolver.slugForId(rootId), categoryResolver.labelFor(rootId, locale),
                CommunityIndexMath.oneDecimal(months.changePercent()), months.pairCount);
    }

    private List<FoodChange> foodsOf(Span span, String state) {
        var monthsByFood = new HashMap<String, ItemMonths>();
        var nameByFood = new HashMap<String, String>();
        changeRepository.monthlyTotalsByFood(span.from().atDay(1), span.to().atDay(1), state == null, stateParam(state))
                .forEach(row -> {
                    var name = ((String) row[1]).trim();
                    if (name.isEmpty()) return;
                    var food = name.toLowerCase(Locale.ROOT);
                    nameByFood.putIfAbsent(food, name);
                    addRow(monthsByFood.computeIfAbsent(food, key -> new ItemMonths()), row);
                });
        return monthsByFood.entrySet().stream()
                .filter(entry -> entry.getValue().pairCount >= properties.getMinPairsPerItem())
                .map(entry -> new FoodChange(nameByFood.get(entry.getKey()),
                        CommunityIndexMath.oneDecimal(entry.getValue().changePercent()), entry.getValue().pairCount))
                .sorted(Comparator.comparing(FoodChange::changePercent).reversed().thenComparing(FoodChange::name))
                .toList();
    }

    private static void addRow(ItemMonths months, Object[] row) {
        months.add(YearMonth.from((LocalDate) row[0]), ((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue(),
                ((Number) row[4]).intValue());
    }

    private static String stateParam(String state) {
        return state == null ? NO_STATE : state;
    }

    // ---------- the household's basket ----------

    /**
     * The products the household bought in the requested period, each weighing by what the household spent
     * on it, moved by the community's relatives of the household's state — or the whole
     * country's in a month the state does not have enough data. Every month of the span must
     * have enough of those products priced.
     */
    private Basket basketOf(User viewer, String viewerState, WindowData data, Span span) {
        var spendByProduct = householdSpendByProduct(viewer, data.window());
        if (spendByProduct.isEmpty()) return insufficientBasket(0);
        var relativesByMonth = basketRelativesByMonth(spendByProduct, viewerState, data, span);

        var linkByMonth = new HashMap<YearMonth, Double>();
        var fewestPricedProducts = Integer.MAX_VALUE;
        for (var month : span.months()) {
            var relatives = relativesByMonth.getOrDefault(month, Map.of());
            fewestPricedProducts = Math.min(fewestPricedProducts, CommunityIndexMath.pricedProducts(spendByProduct, relatives));
            CommunityIndexMath.basketLink(spendByProduct, relatives, properties.getBasketMinProducts())
                    .ifPresent(link -> linkByMonth.put(month, link));
        }
        if (linkByMonth.size() < span.length()) return insufficientBasket(fewestPricedProducts);
        var series = CommunityIndexMath.chain(span, linkByMonth);
        return new Basket(PriceInflationResponse.STATUS_OK,
                CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercent(series)), fewestPricedProducts, points(series));
    }

    private static Basket insufficientBasket(int productCount) {
        return new Basket(PriceInflationResponse.STATUS_INSUFFICIENT, null, productCount, List.of());
    }

    private Map<UUID, Double> householdSpendByProduct(User viewer, Span window) {
        var spendByProduct = new HashMap<UUID, Double>();
        changeRepository.householdSpendByProduct(viewer.getHousehold().getId(),
                        window.from().atDay(1).atStartOfDay(), window.to().plusMonths(1).atDay(1).atStartOfDay())
                .forEach(row -> spendByProduct.put((UUID) row[0], ((BigDecimal) row[1]).doubleValue()));
        return spendByProduct;
    }

    private Map<YearMonth, Map<UUID, Double>> basketRelativesByMonth(Map<UUID, Double> spendByProduct, String viewerState,
                                                                    WindowData data, Span span) {
        var totalsByMonth = new HashMap<YearMonth, Map<UUID, double[]>>();
        changeRepository.findAllByProductIdInAndReferenceMonthBetween(spendByProduct.keySet(),
                        span.from().atDay(1), span.to().atDay(1))
                .forEach(change -> {
                    var month = YearMonth.from(change.getReferenceMonth());
                    var ownStateCounts = viewerState != null && data.isValid(month, viewerState);
                    if (ownStateCounts && !viewerState.equals(change.getState())) return;
                    var totals = totalsByMonth.computeIfAbsent(month, key -> new HashMap<>())
                            .computeIfAbsent(change.getProductId(), key -> new double[2]);
                    totals[0] += change.getWeightedLogSum();
                    totals[1] += change.getWeightSum();
                });
        var relativesByMonth = new HashMap<YearMonth, Map<UUID, Double>>();
        totalsByMonth.forEach((month, totalsByProduct) -> {
            var relatives = new HashMap<UUID, Double>();
            totalsByProduct.forEach((productId, totals) -> CommunityIndexMath.link(totals[0], totals[1])
                    .ifPresent(link -> relatives.put(productId, link)));
            relativesByMonth.put(month, relatives);
        });
        return relativesByMonth;
    }

    // ---------- the states list ----------

    /** "Alimentação no domicílio" of every area over the window: area code → month → link. */
    private Map<String, Map<YearMonth, Double>> foodChangesByArea(Span window) {
        return officialRepository.findAllByItemCodeAndReferenceMonthBetween(properties.getSidra().getFoodAtHomeItem(),
                        window.from().atDay(1), window.to().atDay(1)).stream()
                .collect(Collectors.groupingBy(OfficialInflationMonth::getAreaCode,
                        Collectors.collectingAndThen(Collectors.toList(), PriceInflationQueryService::linkByMonth)));
    }

    private PriceInflationStateResponse stateLine(String state, WindowData data, Map<String, OfficialArea> areaByState,
                                                  Map<String, Map<YearMonth, Double>> foodChangesByArea,
                                                  boolean officialLoaded) {
        var community = communityOf(data, state);
        var span = community.span();
        var area = Optional.ofNullable(areaByState.get(state))
                .orElseGet(() -> new OfficialArea(OfficialInflationLoader.NATIONAL_AREA_CODE, NATIONAL_AREA_NAME, true));
        var officialChange = officialLoaded
                ? CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercent(
                        CommunityIndexMath.chain(span, foodChangesByArea.getOrDefault(area.code(), Map.of()))))
                : null;
        var communityChange = community.sufficient()
                ? CommunityIndexMath.oneDecimal(CommunityIndexMath.changePercent(community.series())) : null;
        return new PriceInflationStateResponse(state, StateStatusService.stateName(UnidadeFederativa.valueOf(state)),
                area.name(), area.nationalFallback(), officialChange,
                community.sufficient() ? PriceInflationResponse.STATUS_OK : PriceInflationResponse.STATUS_INSUFFICIENT,
                communityChange, difference(communityChange, officialChange), span.from().toString(), span.to().toString());
    }

    private static List<SeriesPoint> points(List<IndexPoint> series) {
        return series.stream()
                .map(point -> new SeriesPoint(point.month().toString(), CommunityIndexMath.twoDecimals(point.index())))
                .toList();
    }
}
