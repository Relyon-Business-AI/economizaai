package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses a real SIDRA answer saved as a fixture — never touches the network. */
class SidraInflationClientTest {

    private static final String FIXTURE = "/fixtures/ibge/sidra-7060-food-last-2-months.json";

    private PriceInflationProperties properties;

    @BeforeEach
    void configure() {
        properties = new PriceInflationProperties();
        properties.getSidra().setGroupItems(List.of("7172", "7432"));
    }

    private static String fixture() throws IOException {
        try (var stream = SidraInflationClientTest.class.getResourceAsStream(FIXTURE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A client whose fetch answers a fixed body instead of calling IBGE. */
    private SidraInflationClient clientAnswering(String body) {
        return new SidraInflationClient(RestClient.builder(), properties) {
            @Override
            protected String fetchWithRetry(String url) {
                return body;
            }
        };
    }

    @Test
    void readsEveryAreaItemAndMonth_skippingTheHeaderRow() throws Exception {
        var readings = clientAnswering(fixture()).fetchMonthlyChanges(2);

        assertThat(readings).hasSize(102);
        assertThat(readings.stream().map(SidraInflationClient.OfficialReading::areaCode).distinct()).hasSize(17)
                .allMatch(areaCode -> areaCode.matches("\\d+"));
        assertThat(readings.stream().map(SidraInflationClient.OfficialReading::itemCode).distinct())
                .containsExactlyInAnyOrder("7171", "7172", "7432");
    }

    @Test
    void brasilHasNoState_andEveryOtherAreaBelongsToTheStateOfItsCode() throws Exception {
        var readings = clientAnswering(fixture()).fetchMonthlyChanges(2);

        var national = readings.stream().filter(reading -> reading.areaCode().equals("1")).toList();
        assertThat(national).isNotEmpty().allSatisfy(reading -> {
            assertThat(reading.state()).isNull();
            assertThat(reading.areaName()).isEqualTo("Brasil");
        });
        assertThat(readings).filteredOn(reading -> reading.areaCode().equals("4301"))
                .isNotEmpty().allSatisfy(reading -> {
                    assertThat(reading.state()).isEqualTo("RS");
                    assertThat(reading.areaName()).isEqualTo("Porto Alegre - RS");
                });
        assertThat(readings).filteredOn(reading -> reading.areaCode().equals("5300108"))
                .isNotEmpty().allSatisfy(reading -> assertThat(reading.state()).isEqualTo("DF"));
        assertThat(readings.stream().map(SidraInflationClient.OfficialReading::state).distinct()).hasSize(17);
    }

    @Test
    void readsTheMonthTheItemAndTheMonthlyChange() throws Exception {
        var readings = clientAnswering(fixture()).fetchMonthlyChanges(2);

        var foodAtHomeInBrasil = readings.stream()
                .filter(reading -> reading.areaCode().equals("1") && reading.itemCode().equals("7171"))
                .filter(reading -> reading.month().equals(YearMonth.of(2026, Month.SEPTEMBER)))
                .findFirst().orElseThrow();
        assertThat(foodAtHomeInBrasil.itemName()).isEqualTo("11.Alimentação no domicílio");
        assertThat(foodAtHomeInBrasil.changePercent()).isEqualByComparingTo("0.96");
        assertThat(readings).extracting(SidraInflationClient.OfficialReading::month)
                .containsOnly(YearMonth.of(2026, Month.SEPTEMBER), YearMonth.of(2026, Month.OCTOBER));
    }

    @Test
    void aRowWithoutANumberIsSkipped() {
        var body = """
                [{"NC":"Nível Territorial (Código)","V":"Valor","D1C":"x","D1N":"x","D3C":"Mês (Código)","D4C":"x","D4N":"x"},
                 {"NC":"1","V":"...","D1C":"1","D1N":"Brasil","D3C":"202610","D4C":"7171","D4N":"11.Alimentação no domicílio"},
                 {"NC":"7","V":"-0.31","D1C":"4301","D1N":"Porto Alegre - RS","D3C":"202610","D4C":"7171","D4N":"11.Alimentação no domicílio"}]
                """;

        var readings = clientAnswering(body).fetchMonthlyChanges(1);

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.state()).isEqualTo("RS");
            assertThat(reading.changePercent()).isEqualByComparingTo("-0.31");
        });
    }

    @Test
    void whenIbgeCannotBeReachedOrAnswersSomethingUnreadable_nothingComesBack() {
        assertThat(clientAnswering(null).fetchMonthlyChanges(3)).isEmpty();
        assertThat(clientAnswering("<html>Serviço indisponível</html>").fetchMonthlyChanges(3)).isEmpty();
    }

    @Test
    void asksForTheConfiguredItemsInEveryAreaOverTheLastMonths() {
        properties.getSidra().setBaseUrl("https://sidra.example/");

        var url = clientAnswering("[]").monthlyChangeUrl(36);

        assertThat(url).isEqualTo(
                "https://sidra.example/values/t/7060/n1/all/n7/all/n6/all/v/63/p/last%2036/c315/7171,7172,7432?formato=json");
    }
}
