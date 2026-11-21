package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.dto.response.PriceInflationResponse;
import com.relyon.economizaai.dto.response.PriceInflationResponse.CategoryChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.FoodChange;
import com.relyon.economizaai.dto.response.PriceInflationResponse.SeriesPoint;
import com.relyon.economizaai.dto.response.PriceInflationStateResponse;
import com.relyon.economizaai.exception.InvalidSettingValueException;
import com.relyon.economizaai.exception.PriceInflationUnavailableException;
import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.OfficialInflationMonth;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationDestination;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.PriceInflationScope;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.CommunityPriceChangeRepository;
import com.relyon.economizaai.repository.CommunityPriceCoverageRepository;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.OfficialInflationMonthRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.inflation.SidraInflationClient.OfficialReading;
import com.relyon.economizaai.service.settings.RuntimeSetting;
import com.relyon.economizaai.service.settings.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Preços e inflação" against the real application and database: prices observed in 2019 (months
 * no other test writes to) are turned into the community index, read next to the official months
 * and announced. Every setting is changed the way the admin changes it — through
 * {@link RuntimeSettings} — and the outcome must follow.
 *
 * <p>The data: in RS four products at two stores from September to December, all moving
 * together (+10% in October, flat in November, −5% in December). SP has one pair in December,
 * MG two products at two stores in November and December, BA one product that jumps 150%.
 * IBGE has July to December for Brasil (+0.5% a month) and Porto Alegre (+0.2% a month).
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceInflationIntegrationTest {

    private static final String ADMIN_EMAIL = "settings-check@economizaai.app";
    private static final String FOOD_AT_HOME = "7171";
    private static final String CEREALS = "7172";
    private static final String BRASIL = "1";
    private static final String PORTO_ALEGRE = "4301";
    private static final YearMonth JULY = YearMonth.of(2019, Month.JULY);
    private static final YearMonth SEPTEMBER = YearMonth.of(2019, Month.SEPTEMBER);
    private static final YearMonth OCTOBER = YearMonth.of(2019, Month.OCTOBER);
    private static final YearMonth NOVEMBER = YearMonth.of(2019, Month.NOVEMBER);
    private static final YearMonth DECEMBER = YearMonth.of(2019, Month.DECEMBER);
    private static final List<YearMonth> SEPTEMBER_TO_DECEMBER = List.of(SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER);
    private static final List<String> MOVE_TOGETHER = List.of("1.00", "1.10", "1.10", "1.045");
    private static final String RS_STORE_ONE = "90000001000101";
    private static final String RS_STORE_TWO = "90000002000102";

    @Autowired private PriceInflationQueryService queryService;
    @Autowired private CommunityPriceChangeComputer communityComputer;
    @Autowired private PriceInflationNoticeService noticeService;
    @Autowired private PriceInflationProperties properties;
    @Autowired private RuntimeSettings runtimeSettings;
    @Autowired private CategoryResolver categoryResolver;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private OfficialInflationMonthRepository officialRepository;
    @Autowired private CommunityPriceChangeRepository changeRepository;
    @Autowired private CommunityPriceCoverageRepository coverageRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<PriceObservation> observations = new ArrayList<>();
    private final List<Product> products = new ArrayList<>();
    private final List<Receipt> receipts = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<Household> households = new ArrayList<>();

    private Product rice;
    private Product beans;
    private Product soap;
    private User adminInRs;
    private User memberInRs;
    private User adminInSc;
    private User adminWithoutNotas;

    @BeforeEach
    void seed() {
        var mercearia = rootCategory("mercearia", "Mercearia");
        var hortifruti = rootCategory("hortifruti", "Hortifrúti");
        var limpeza = rootCategory("limpeza", "Limpeza");
        categoryResolver.warmUp();

        rice = product("Arroz", mercearia);
        beans = product("Feijão", mercearia);
        var coffee = product("Café", mercearia);
        var tomato = product("Tomate", hortifruti);
        var milk = product("Leite", mercearia);
        var banana = product("Banana", hortifruti);
        var sugar = product("Açúcar", mercearia);
        soap = product("Sabão", limpeza);

        for (var store : List.of(RS_STORE_ONE, RS_STORE_TWO)) {
            observeMovingTogether(rice, store, "10");
            observeMovingTogether(beans, store, "8");
            observeMovingTogether(coffee, store, "20");
            observeMovingTogether(tomato, store, "5");
            observeMovingTogether(soap, store, "4");
        }
        observe(rice, "90000003000103", "SP", NOVEMBER, "10");
        observe(rice, "90000003000103", "SP", DECEMBER, "12");
        for (var store : List.of("90000004000104", "90000005000105")) {
            observe(milk, store, "MG", NOVEMBER, "10");
            observe(milk, store, "MG", DECEMBER, "12");
            observe(banana, store, "MG", NOVEMBER, "10");
            observe(banana, store, "MG", DECEMBER, "9");
            observe(sugar, store.replace("9000000", "9100000"), "BA", NOVEMBER, "10");
            observe(sugar, store.replace("9000000", "9100000"), "BA", DECEMBER, "25");
        }

        for (var month = JULY; !month.isAfter(DECEMBER); month = month.plusMonths(1)) {
            official(BRASIL, "Brasil", null, FOOD_AT_HOME, "11.Alimentação no domicílio", month, "0.5");
            official(BRASIL, "Brasil", null, CEREALS, "1101.Cereais, leguminosas e oleaginosas", month, "1.0");
            official(PORTO_ALEGRE, "Porto Alegre - RS", "RS", FOOD_AT_HOME, "11.Alimentação no domicílio", month, "0.2");
            official(PORTO_ALEGRE, "Porto Alegre - RS", "RS", CEREALS, "1101.Cereais, leguminosas e oleaginosas", month, "1.0");
        }

        adminInRs = user(Role.ADMIN);
        memberInRs = user(Role.USER);
        adminInSc = user(Role.ADMIN);
        adminWithoutNotas = user(Role.ADMIN);
        purchase(adminInRs, UnidadeFederativa.RS, line(rice, "30.00"), line(beans, "10.00"), line(soap, "50.00"));
        purchase(memberInRs, UnidadeFederativa.RS, line(rice, "12.00"));
        purchase(adminInSc, UnidadeFederativa.SC, line(rice, "30.00"), line(beans, "10.00"));

        recomputeCommunity();
    }

    @AfterEach
    void removeWhatTheTestLeftBehind() {
        Arrays.stream(RuntimeSetting.values())
                .filter(setting -> setting.name().startsWith("PRICE_INFLATION_"))
                .forEach(setting -> runtimeSettings.restoreConfigured(setting.getKey(), ADMIN_EMAIL));
        notificationRepository.deleteAll(priceInflationNotices());
        changeRepository.deleteAll();
        coverageRepository.deleteAll();
        officialRepository.deleteAll();
        observationRepository.deleteAll(observations);
        receiptRepository.deleteAll(receipts);
        userRepository.deleteAll(users);
        householdRepository.deleteAll(households);
        productRepository.deleteAll(products);
    }

    // ---------- the window and the span ----------

    @Test
    void withTheConfiguredMinimums_thisLittleDataIsInsufficient_andTheOfficialSeriesIsShownAloneOverTheWholePeriod() {
        var response = queryService.overview(adminInRs, PriceInflationScope.BR, null, 6);

        assertThat(response.scope()).isEqualTo("BR");
        assertThat(response.state()).isNull();
        assertThat(response.requestedMonths()).isEqualTo(6);
        assertThat(response.from()).isEqualTo("2019-07");
        assertThat(response.to()).isEqualTo("2019-12");
        assertThat(response.shortened()).isFalse();
        assertThat(response.community().status()).isEqualTo("INSUFFICIENT");
        assertThat(response.community().changePercent()).isNull();
        assertThat(response.community().series()).isEmpty();
        assertThat(response.official().areaName()).isEqualTo("Brasil");
        assertThat(response.official().nationalFallback()).isFalse();
        assertThat(response.official().changePercent()).isEqualByComparingTo("3.0");
        assertThat(response.official().series()).hasSize(7);
        assertThat(response.official().series().get(0)).isEqualTo(new SeriesPoint("2019-06", new BigDecimal("100.00")));
        assertThat(response.differencePoints()).isNull();
        assertThat(response.categories()).isEmpty();
        assertThat(response.foods()).isEmpty();
    }

    @Test
    void minimumPairsPerMonth_decidesWhichMonthsCount_andAShorterSpanComparesBothSidesOverTheSameMonths() {
        change(RuntimeSetting.PRICE_INFLATION_MIN_STORES_PER_MONTH, "2");
        assertThat(overviewOfRs(6).community().status()).isEqualTo("INSUFFICIENT");

        change(RuntimeSetting.PRICE_INFLATION_MIN_PAIRS_PER_MONTH, "8");
        var response = overviewOfRs(6);

        assertThat(response.scope()).isEqualTo("STATE");
        assertThat(response.state()).isEqualTo("RS");
        assertThat(response.stateName()).isEqualTo("Rio Grande do Sul");
        assertThat(response.from()).isEqualTo("2019-10");
        assertThat(response.to()).isEqualTo("2019-12");
        assertThat(response.shortened()).isTrue();
        assertThat(response.community().status()).isEqualTo("OK");
        assertThat(response.community().changePercent()).isEqualByComparingTo("4.5");
        assertThat(response.community().pairCount()).isEqualTo(24);
        assertThat(response.community().storeCount()).isEqualTo(2);
        assertThat(response.official().areaName()).isEqualTo("Porto Alegre - RS");
        assertThat(response.official().nationalFallback()).isFalse();
        assertThat(response.official().changePercent()).isEqualByComparingTo("0.6");
        assertThat(response.differencePoints()).isEqualByComparingTo("3.9");
        assertThat(monthsOf(response.community().series()))
                .isEqualTo(monthsOf(response.official().series()))
                .containsExactly("2019-09", "2019-10", "2019-11", "2019-12");
        assertThat(response.community().series()).extracting(SeriesPoint::index)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("110"), new BigDecimal("104.5"));
        assertThat(response.officialGroups()).singleElement().satisfies(group -> {
            assertThat(group.code()).isEqualTo(CEREALS);
            assertThat(group.name()).isEqualTo("Cereais, leguminosas e oleaginosas");
            assertThat(group.changePercent()).isEqualByComparingTo("3.0");
        });
    }

    @Test
    void aPeriodFullyCoveredIsNotShortened() {
        lowerTheMonthlyMinimums();

        var response = overviewOfRs(3);

        assertThat(response.from()).isEqualTo("2019-10");
        assertThat(response.shortened()).isFalse();
        assertThat(response.community().changePercent()).isEqualByComparingTo("4.5");
    }

    @Test
    void minimumStoresPerMonth_isRequiredToo() {
        lowerTheMonthlyMinimums();
        assertThat(overviewOfRs(6).community().status()).isEqualTo("OK");

        change(RuntimeSetting.PRICE_INFLATION_MIN_STORES_PER_MONTH, "3");

        assertThat(overviewOfRs(6).community().status()).isEqualTo("INSUFFICIENT");
    }

    @Test
    void brasilAddsUpEveryState() {
        lowerTheMonthlyMinimums();

        var response = queryService.overview(adminInRs, PriceInflationScope.BR, null, 6);

        assertThat(response.scope()).isEqualTo("BR");
        assertThat(response.community().status()).isEqualTo("OK");
        assertThat(response.community().pairCount()).isEqualTo(8 + 8 + 13);
        assertThat(response.community().storeCount()).isEqualTo(5);
        assertThat(response.community().series().get(1).index()).isEqualByComparingTo("110");
        assertThat(response.official().areaName()).isEqualTo("Brasil");
    }

    // ---------- scope ----------

    @Test
    void aStateScopeWithoutAState_isTheViewersState_andBrasilWhenTheViewerHasNone() {
        lowerTheMonthlyMinimums();

        var ofTheViewer = queryService.overview(adminInRs, PriceInflationScope.STATE, null, 6);
        var withoutNotas = queryService.overview(adminWithoutNotas, PriceInflationScope.STATE, null, 6);

        assertThat(ofTheViewer.scope()).isEqualTo("STATE");
        assertThat(ofTheViewer.state()).isEqualTo("RS");
        assertThat(ofTheViewer.viewerState()).isEqualTo("RS");
        assertThat(ofTheViewer.viewerStateName()).isEqualTo("Rio Grande do Sul");
        assertThat(withoutNotas.scope()).isEqualTo("BR");
        assertThat(withoutNotas.state()).isNull();
        assertThat(withoutNotas.stateName()).isNull();
        assertThat(withoutNotas.viewerState()).isNull();
        assertThat(withoutNotas.viewerStateName()).isNull();
    }

    @Test
    void theViewersStateComesInEveryAnswer_whateverWasAskedFor() {
        var response = queryService.overview(adminInRs, PriceInflationScope.STATE, UnidadeFederativa.MG, 6);

        assertThat(response.state()).isEqualTo("MG");
        assertThat(response.viewerState()).isEqualTo("RS");
        assertThat(response.viewerStateName()).isEqualTo("Rio Grande do Sul");
    }

    @Test
    void aStateWithoutAnIbgeArea_usesTheNationalNumberAndSaysSo() {
        lowerTheMonthlyMinimums();

        var response = queryService.overview(adminInRs, PriceInflationScope.STATE, UnidadeFederativa.MG, 6);

        assertThat(response.official().areaName()).isEqualTo("Brasil");
        assertThat(response.official().nationalFallback()).isTrue();
        assertThat(response.from()).isEqualTo("2019-12");
        assertThat(response.official().changePercent()).isEqualByComparingTo("0.5");
    }

    // ---------- periods ----------

    @Test
    void periods_decideWhatCanBeAskedFor_andTheLargestIsTheInitialOne() {
        assertThat(queryService.overview(adminInRs, PriceInflationScope.BR, null, 4).requestedMonths()).isEqualTo(12);
        assertThat(queryService.overview(adminInRs, PriceInflationScope.BR, null, null).requestedMonths()).isEqualTo(12);

        change(RuntimeSetting.PRICE_INFLATION_PERIODS, "3,4");

        var response = queryService.overview(adminInRs, PriceInflationScope.BR, null, 4);
        assertThat(response.requestedMonths()).isEqualTo(4);
        assertThat(response.from()).isEqualTo("2019-09");
        assertThat(queryService.overview(adminInRs, PriceInflationScope.BR, null, null).requestedMonths()).isEqualTo(4);
        assertThat(queryService.overview(adminInRs, PriceInflationScope.BR, null, 12).requestedMonths()).isEqualTo(4);
    }

    // ---------- audience ----------

    @Test
    void audience_keepsTheScreenToAdminsUntilItIsOpenedToEveryone() {
        assertThat(queryService.overview(adminInRs, PriceInflationScope.BR, null, 6)).isNotNull();
        assertThat(queryService.states(adminInRs, 6)).hasSize(27);
        assertThatThrownBy(() -> queryService.overview(memberInRs, PriceInflationScope.BR, null, 6))
                .isInstanceOf(PriceInflationUnavailableException.class);
        assertThatThrownBy(() -> queryService.states(memberInRs, 6))
                .isInstanceOf(PriceInflationUnavailableException.class);

        change(RuntimeSetting.PRICE_INFLATION_AUDIENCE, "EVERYONE");

        assertThat(queryService.overview(memberInRs, PriceInflationScope.BR, null, 6).scope()).isEqualTo("BR");
        assertThat(queryService.states(memberInRs, 6)).hasSize(27);
    }

    // ---------- categories and foods ----------

    @Test
    void minimumPairsPerItem_decidesWhichCategoriesAndFoodsAreListed_sortedFromTheBiggestRise() {
        lowerTheMonthlyMinimums();
        var withTheConfiguredMinimum = queryService.overview(adminInRs, PriceInflationScope.STATE, UnidadeFederativa.MG, 6);
        assertThat(withTheConfiguredMinimum.categories()).isEmpty();
        assertThat(withTheConfiguredMinimum.foods()).isEmpty();

        change(RuntimeSetting.PRICE_INFLATION_MIN_PAIRS_PER_ITEM, "2");
        var response = queryService.overview(adminInRs, PriceInflationScope.STATE, UnidadeFederativa.MG, 6);

        assertThat(response.categories()).extracting(CategoryChange::slug).containsExactly("mercearia", "hortifruti");
        assertThat(response.categories()).extracting(CategoryChange::name).containsExactly("Mercearia", "Hortifrúti");
        assertThat(response.categories().get(0).changePercent()).isEqualByComparingTo("20.0");
        assertThat(response.categories().get(1).changePercent()).isEqualByComparingTo("-10.0");
        assertThat(response.categories()).extracting(CategoryChange::pairCount).containsExactly(2, 2);
        assertThat(response.foods()).extracting(FoodChange::name).containsExactly("Leite", "Banana");
        assertThat(response.foods().get(0).changePercent()).isEqualByComparingTo("20.0");
        assertThat(response.foods().get(1).changePercent()).isEqualByComparingTo("-10.0");
    }

    @Test
    void anItemChangesOverTheMonthsOfTheSpan() {
        lowerTheMonthlyMinimums();

        var response = overviewOfRs(6);

        assertThat(response.categories()).extracting(CategoryChange::slug).containsExactly("hortifruti", "mercearia");
        assertThat(response.categories()).allSatisfy(category ->
                assertThat(category.changePercent()).isEqualByComparingTo("4.5"));
        assertThat(response.categories()).extracting(CategoryChange::pairCount).containsExactly(6, 18);
        assertThat(response.foods()).extracting(FoodChange::name).containsExactly("Arroz", "Café", "Feijão", "Tomate");
    }

    // ---------- the household's basket ----------

    @Test
    void basketMinimumProducts_decidesWhenSuaCestaAppears() {
        lowerTheMonthlyMinimums();
        var withTheConfiguredMinimum = overviewOfRs(6).basket();
        assertThat(withTheConfiguredMinimum.status()).isEqualTo("INSUFFICIENT");
        assertThat(withTheConfiguredMinimum.productCount()).isEqualTo(2);
        assertThat(withTheConfiguredMinimum.changePercent()).isNull();
        assertThat(withTheConfiguredMinimum.series()).isEmpty();

        change(RuntimeSetting.PRICE_INFLATION_BASKET_MIN_PRODUCTS, "2");
        var response = overviewOfRs(6);

        assertThat(response.basket().status()).isEqualTo("OK");
        assertThat(response.basket().productCount()).isEqualTo(2);
        assertThat(response.basket().changePercent()).isEqualByComparingTo("4.5");
        assertThat(monthsOf(response.basket().series())).isEqualTo(monthsOf(response.community().series()));
    }

    @Test
    void theBasketUsesTheHouseholdsState_andTheWholeCountryWhenTheStateHasTooLittleData() {
        lowerTheMonthlyMinimums();
        change(RuntimeSetting.PRICE_INFLATION_BASKET_MIN_PRODUCTS, "2");

        var inRs = queryService.overview(adminInRs, PriceInflationScope.BR, null, 6).basket();
        var inSc = queryService.overview(adminInSc, PriceInflationScope.BR, null, 6).basket();

        // RS has its own valid months: rice fell 5% there in December. SC has no data, so its
        // household gets the national rice, pulled up by the SP store where it rose 20%.
        assertThat(inRs.changePercent()).isEqualByComparingTo("4.5");
        assertThat(inSc.status()).isEqualTo("OK");
        assertThat(inSc.changePercent()).isGreaterThan(new BigDecimal("4.5"));
    }

    // ---------- settings that act when the community table is computed ----------

    @Test
    void maximumMonthlyChange_dropsAJumpAsAnError_untilTheMaximumAllowsIt() {
        assertThat(pairsIn("BA", DECEMBER)).isZero();

        change(RuntimeSetting.PRICE_INFLATION_MAX_MONTHLY_CHANGE_PERCENT, "150");
        recomputeCommunity();

        assertThat(pairsIn("BA", DECEMBER)).isEqualTo(2);
    }

    @Test
    void foodCategories_decideWhichProductsEnterTheIndex() {
        assertThat(pairsIn("RS", DECEMBER)).isEqualTo(8);

        change(RuntimeSetting.PRICE_INFLATION_FOOD_CATEGORIES, properties.getFoodCategories().stream()
                .filter(slug -> !slug.equals("hortifruti")).reduce((first, second) -> first + "," + second).orElseThrow());
        recomputeCommunity();
        assertThat(pairsIn("RS", DECEMBER)).isEqualTo(6);

        change(RuntimeSetting.PRICE_INFLATION_FOOD_CATEGORIES, String.join(",", properties.getFoodCategories()) + ",limpeza");
        recomputeCommunity();
        assertThat(pairsIn("RS", DECEMBER)).isEqualTo(8);
    }

    @Test
    void healthProductsCanNeverBeMadePartOfTheIndex() {
        assertThatThrownBy(() -> change(RuntimeSetting.PRICE_INFLATION_FOOD_CATEGORIES, "mercearia,saude"))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThat(properties.getFoodCategories()).doesNotContain("saude");
    }

    @Test
    void recomputingAMonthReplacesItsRows() {
        var rowsBefore = changeRepository.count();
        var coverageBefore = coverageRepository.count();

        recomputeCommunity();
        recomputeCommunity();

        assertThat(changeRepository.count()).isEqualTo(rowsBefore);
        assertThat(coverageRepository.count()).isEqualTo(coverageBefore);
    }

    // ---------- the states list ----------

    @Test
    void states_listsThe27_eachWithItsOwnSpanAndItsOfficialArea() {
        lowerTheMonthlyMinimums();

        var states = queryService.states(adminInRs, 6);

        assertThat(states).hasSize(27);
        assertThat(states).extracting(PriceInflationStateResponse::state).isSorted().doesNotHaveDuplicates();
        assertThat(states).allSatisfy(line -> assertThat(line.stateName()).isNotBlank());

        var rs = lineOf(states, "RS");
        assertThat(rs.stateName()).isEqualTo("Rio Grande do Sul");
        assertThat(rs.officialAreaName()).isEqualTo("Porto Alegre - RS");
        assertThat(rs.nationalFallback()).isFalse();
        assertThat(rs.officialChangePercent()).isEqualByComparingTo("0.6");
        assertThat(rs.communityStatus()).isEqualTo("OK");
        assertThat(rs.communityChangePercent()).isEqualByComparingTo("4.5");
        assertThat(rs.differencePoints()).isEqualByComparingTo("3.9");
        assertThat(rs.from()).isEqualTo("2019-10");
        assertThat(rs.to()).isEqualTo("2019-12");

        var mg = lineOf(states, "MG");
        assertThat(mg.officialAreaName()).isEqualTo("Brasil");
        assertThat(mg.nationalFallback()).isTrue();
        assertThat(mg.communityStatus()).isEqualTo("OK");
        assertThat(mg.from()).isEqualTo("2019-12");
        assertThat(mg.officialChangePercent()).isEqualByComparingTo("0.5");

        var ac = lineOf(states, "AC");
        assertThat(ac.nationalFallback()).isTrue();
        assertThat(ac.communityStatus()).isEqualTo("INSUFFICIENT");
        assertThat(ac.communityChangePercent()).isNull();
        assertThat(ac.differencePoints()).isNull();
        assertThat(ac.officialChangePercent()).isEqualByComparingTo("3.0");
        assertThat(ac.from()).isEqualTo("2019-07");
    }

    // ---------- the monthly notice ----------

    @Test
    void theNotice_goesOnlyToAdminsWhileTheScreenIsTheirs_inTheInboxAndNowhereElse() {
        lowerTheMonthlyMinimums();

        noticeService.announce(DECEMBER);

        assertThat(noticesOf(memberInRs)).isEmpty();
        assertThat(noticesOf(adminInRs)).singleElement().satisfies(notice -> {
            assertThat(notice.getType()).isEqualTo(NotificationType.PRICE_INFLATION);
            assertThat(notice.getType().destination()).isEqualTo(NotificationDestination.PRICE_INFLATION);
            assertThat(notice.getChannel()).isEqualTo(NotificationChannel.INBOX);
            assertThat(notice.isDelivered()).isFalse();
            assertThat(notice.isDispatchDeferred()).isFalse();
            assertThat(notice.getFailureReason()).isNull();
            assertThat(notice.getTitle()).isEqualTo("Preços e inflação");
            assertThat(notice.getBody()).isEqualTo(
                    "Alimentos subiram 0,5% no Brasil em dezembro. Nos mercados do RS, os preços caíram 5,0%.");
            assertThat(notice.getPayload()).contains("\"destination\":\"PRICE_INFLATION\"").contains("\"month\":\"2019-12\"");
        });
    }

    @Test
    void theNotice_talksAboutTheStateOnlyWhenTheStateHasEnoughDataThatMonth() {
        noticeService.announce(DECEMBER);

        assertThat(noticesOf(adminInRs)).singleElement().satisfies(notice ->
                assertThat(notice.getBody()).isEqualTo("Alimentos subiram 0,5% no Brasil em dezembro."));
        assertThat(noticesOf(adminWithoutNotas)).singleElement().satisfies(notice ->
                assertThat(notice.getBody()).isEqualTo("Alimentos subiram 0,5% no Brasil em dezembro."));
    }

    @Test
    void theNotice_saysWhenPricesFellOrStayedTheSame() {
        lowerTheMonthlyMinimums();
        var november = officialRepository.findByAreaCodeAndItemCodeAndReferenceMonth(BRASIL, FOOD_AT_HOME, NOVEMBER.atDay(1))
                .orElseThrow();
        november.setChangePercent(new BigDecimal("-0.34"));
        officialRepository.save(november);

        noticeService.announce(NOVEMBER);

        assertThat(noticesOf(adminInRs)).singleElement().satisfies(notice -> assertThat(notice.getBody()).isEqualTo(
                "Alimentos caíram 0,3% no Brasil em novembro. Nos mercados do RS, os preços ficaram estáveis."));
    }

    @Test
    void theNotice_reachesEveryoneOnceTheScreenIsOpenToEveryone() {
        change(RuntimeSetting.PRICE_INFLATION_AUDIENCE, "EVERYONE");

        noticeService.announce(DECEMBER);

        assertThat(noticesOf(memberInRs)).hasSize(1);
        assertThat(noticesOf(adminInRs)).hasSize(1);
    }

    @Test
    void monthlyNotice_switchedOff_sendsNothing() {
        change(RuntimeSetting.PRICE_INFLATION_MONTHLY_NOTICE, "false");

        assertThat(noticeService.announce(DECEMBER)).isZero();

        assertThat(noticesOf(adminInRs)).isEmpty();
    }

    @Test
    void theDailyJob_announcesANewOfficialMonthOnce_andNeverOnTheFirstLoad() {
        officialRepository.deleteAll();
        var ibge = new IbgeStub();
        var job = new PriceInflationRefreshJob(
                new OfficialInflationLoader(ibge, officialRepository, properties, transactionManager),
                communityComputer, noticeService, properties);

        ibge.publishedUpTo = NOVEMBER;
        job.refreshDaily();
        assertThat(officialRepository.count()).isEqualTo(5);
        assertThat(noticesOf(adminInRs)).as("first load ever announces nothing").isEmpty();

        job.refreshDaily();
        assertThat(noticesOf(adminInRs)).as("no new month, nothing to announce").isEmpty();

        ibge.publishedUpTo = DECEMBER;
        job.refreshDaily();
        assertThat(officialRepository.count()).isEqualTo(6);
        assertThat(noticesOf(adminInRs)).singleElement().satisfies(notice ->
                assertThat(notice.getBody()).startsWith("Alimentos subiram 0,5% no Brasil em dezembro."));
        assertThat(noticesOf(memberInRs)).isEmpty();

        job.refreshDaily();
        assertThat(noticesOf(adminInRs)).as("the same month is announced once").hasSize(1);
    }

    @Test
    void theOnDemandRecompute_loadsAndRecomputes_butNeverAnnounces() {
        officialRepository.deleteAll();
        var ibge = new IbgeStub();
        ibge.publishedUpTo = NOVEMBER;
        var job = new PriceInflationRefreshJob(
                new OfficialInflationLoader(ibge, officialRepository, properties, transactionManager),
                communityComputer, noticeService, properties);
        job.refreshOnDemand(2);

        ibge.publishedUpTo = DECEMBER;
        var result = job.refreshOnDemand(2);

        assertThat(result.months()).isEqualTo(2);
        assertThat(result.officialReadingsFetched()).isEqualTo(6);
        assertThat(result.officialRowsStored()).isEqualTo(1);
        assertThat(result.newestOfficialMonth()).isEqualTo("2019-12");
        assertThat(result.communityMonthsRecomputed()).isEqualTo(2);
        assertThat(noticesOf(adminInRs)).isEmpty();
    }

    @Test
    void whenIbgeDoesNotAnswer_theJobKeepsWhatItHas() {
        var ibge = new IbgeStub();
        ibge.publishedUpTo = null;
        var job = new PriceInflationRefreshJob(
                new OfficialInflationLoader(ibge, officialRepository, properties, transactionManager),
                communityComputer, noticeService, properties);
        var officialRowsBefore = officialRepository.count();

        job.refreshDaily();

        assertThat(officialRepository.count()).isEqualTo(officialRowsBefore);
        assertThat(noticesOf(adminInRs)).isEmpty();
    }

    // ---------- helpers ----------

    /** IBGE as of a given month: Brasil's "Alimentação no domicílio" from July 2019 up to it, +0.5% a month. */
    private final class IbgeStub extends SidraInflationClient {

        private YearMonth publishedUpTo;

        private IbgeStub() {
            super(RestClient.builder(), properties);
        }

        @Override
        public List<OfficialReading> fetchMonthlyChanges(int months) {
            var readings = new ArrayList<OfficialReading>();
            if (publishedUpTo == null) return readings;
            for (var month = JULY; !month.isAfter(publishedUpTo); month = month.plusMonths(1)) {
                readings.add(new OfficialReading(BRASIL, "Brasil", null, FOOD_AT_HOME, "11.Alimentação no domicílio",
                        month, new BigDecimal("0.5")));
            }
            return readings;
        }
    }

    private void change(RuntimeSetting setting, String value) {
        runtimeSettings.change(setting.getKey(), value, ADMIN_EMAIL);
    }

    /** Enough for RS (8 pairs at 2 stores a month) and MG (4 pairs at 2 stores in December). */
    private void lowerTheMonthlyMinimums() {
        change(RuntimeSetting.PRICE_INFLATION_MIN_PAIRS_PER_MONTH, "4");
        change(RuntimeSetting.PRICE_INFLATION_MIN_STORES_PER_MONTH, "2");
    }

    private void recomputeCommunity() {
        communityComputer.recomputeMonthsEndingAt(DECEMBER, 4);
    }

    private PriceInflationResponse overviewOfRs(int months) {
        return queryService.overview(adminInRs, PriceInflationScope.STATE, UnidadeFederativa.RS, months);
    }

    private int pairsIn(String state, YearMonth month) {
        return coverageRepository.findAllByReferenceMonthBetween(month.atDay(1), month.atDay(1)).stream()
                .filter(coverage -> coverage.getState().equals(state))
                .mapToInt(coverage -> coverage.getPairCount())
                .sum();
    }

    private static List<String> monthsOf(List<SeriesPoint> series) {
        return series.stream().map(SeriesPoint::month).toList();
    }

    private static PriceInflationStateResponse lineOf(List<PriceInflationStateResponse> states, String state) {
        return states.stream().filter(line -> line.state().equals(state)).findFirst().orElseThrow();
    }

    private List<Notification> priceInflationNotices() {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getType() == NotificationType.PRICE_INFLATION)
                .toList();
    }

    private List<Notification> noticesOf(User user) {
        return notificationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged())
                .getContent().stream()
                .filter(notification -> notification.getType() == NotificationType.PRICE_INFLATION)
                .toList();
    }

    private UUID rootCategory(String slug, String namePt) {
        return categoryRepository.findBySlug(slug)
                .orElseGet(() -> categoryRepository.save(Category.builder().slug(slug).namePt(namePt).nameEn(namePt).build()))
                .getId();
    }

    private Product product(String genericName, UUID categoryId) {
        var product = productRepository.save(Product.builder()
                .normalizedName(genericName.toUpperCase() + " INFLACAO " + ThreadLocalRandom.current().nextInt(1_000_000))
                .genericName(genericName)
                .categoryId(categoryId)
                .build());
        products.add(product);
        return product;
    }

    private void observeMovingTogether(Product product, String store, String basePrice) {
        for (var index = 0; index < SEPTEMBER_TO_DECEMBER.size(); index++) {
            var price = new BigDecimal(basePrice).multiply(new BigDecimal(MOVE_TOGETHER.get(index)))
                    .setScale(4, RoundingMode.HALF_UP);
            observe(product, store, "RS", SEPTEMBER_TO_DECEMBER.get(index), price.toPlainString());
        }
    }

    private void observe(Product product, String store, String state, YearMonth month, String unitPrice) {
        observations.add(observationRepository.save(PriceObservation.builder()
                .product(product)
                .marketCnpj(store)
                .marketCnpjRoot(store.substring(0, 8))
                .unitPrice(new BigDecimal(unitPrice))
                .quantity(BigDecimal.ONE)
                .observedAt(month.atDay(10).atTime(12, 0))
                .state(state)
                .build()));
    }

    private void official(String areaCode, String areaName, String state, String itemCode, String itemName,
                          YearMonth month, String changePercent) {
        officialRepository.save(OfficialInflationMonth.builder()
                .areaCode(areaCode).areaName(areaName).state(state)
                .itemCode(itemCode).itemName(itemName)
                .referenceMonth(month.atDay(1)).changePercent(new BigDecimal(changePercent))
                .build());
    }

    private User user(Role role) {
        var suffix = ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        var household = householdRepository.save(Household.builder().inviteCode(String.valueOf(suffix)).build());
        households.add(household);
        var email = "inflation" + suffix + "@economizaai.app";
        var user = userRepository.save(User.builder()
                .name(email).email(email).password("x").household(household).role(role)
                .pushDeviceToken("ExponentPushToken[inflation-" + suffix + "]")
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(NOVEMBER.atDay(1).atStartOfDay())
                .build());
        users.add(user);
        return user;
    }

    private ReceiptItem line(Product product, String totalPrice) {
        return ReceiptItem.builder()
                .lineNumber(1).rawDescription(product.getGenericName())
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal(totalPrice)).totalPrice(new BigDecimal(totalPrice))
                .product(product).build();
    }

    private void purchase(User buyer, UnidadeFederativa state, ReceiptItem... lines) {
        var receipt = Receipt.builder()
                .user(buyer).household(buyer.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(state)
                .cnpjEmitente(RS_STORE_ONE).marketName("Mercado")
                .issuedAt(NOVEMBER.atDay(15).atTime(10, 0)).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        Arrays.stream(lines).forEach(receipt::addItem);
        receipts.add(receiptRepository.save(receipt));
    }
}
