package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.PriceExposureRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.schedule.DealsDigestScheduler;
import com.relyon.economizaai.service.notifications.schedule.DigestScheduleService;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import com.relyon.economizaai.service.sefaz.ReceiptIngestionService;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tapping "X mais barato e mais N ofertas" from end to end, through the real endpoints, the real
 * digest and the real database: purchases that create offers, the digest that announces them,
 * and the offers list narrowed to that notice.
 *
 * <p>Only the government portal fetch and the send-hour inference (a PostgreSQL-only query the
 * test database cannot run, unrelated to what a notice opens) are mocked. NOT {@code @Transactional}: the digest persists
 * what it announced in its own transaction, as in production.
 */
@SpringBootTest(webEnvironment = MOCK, properties = "economizaai.merchant.classify.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NoticeDealsFlowIntegrationTest {

    private static final Market CHEAPER_MARKET = new Market("61585865000151", "Mercado Aviso Barato", "43260461585865000151651130003394");
    private static final Market DEARER_MARKET = new Market("47508411000156", "Mercado Aviso Caro", "43260447508411000156651130003394");

    @Autowired private MockMvc mockMvc;
    @Autowired private ReceiptIngestionService receiptIngestionService;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private DealSurfaceStateRepository surfaceStateRepository;
    @Autowired private PriceExposureRepository priceExposureRepository;
    @Autowired private PriceObservationRepository observationRepository;
    @Autowired private PriceObservationAuditRepository observationAuditRepository;
    @Autowired private DealsDigestScheduler dealsDigestScheduler;

    @MockitoBean private SefazIngestionService sefazIngestionService;
    @MockitoBean private DigestScheduleService digestScheduleService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> testUserIds = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestLeftBehind() {
        for (var userId : testUserIds) {
            eventRepository.deleteAll(eventRepository.findAllByUserId(userId));
            surfaceStateRepository.deleteAll(surfaceStateRepository.findAllByUserId(userId));
            priceExposureRepository.deleteAll(priceExposureRepository.findAll().stream()
                    .filter(exposure -> exposure.getUserId().equals(userId)).toList());
        }
        var testMarkets = Set.of(CHEAPER_MARKET.cnpj(), DEARER_MARKET.cnpj());
        var testObservations = observationRepository.findAll().stream()
                .filter(observation -> testMarkets.contains(observation.getMarketCnpj())).toList();
        var testObservationIds = testObservations.stream().map(PriceObservation::getId).collect(Collectors.toSet());
        observationAuditRepository.deleteAll(observationAuditRepository.findAll().stream()
                .filter(audit -> testObservationIds.contains(audit.getObservation().getId())).toList());
        observationRepository.deleteAll(testObservations);
    }

    @Test
    void noticeWithSeveralOffers_opensOnlyTheOffersItAnnounced() throws Exception {
        var shopper = newShopper();
        var announcedGroceries = List.of(
                new Grocery("CAFE AVISO OFERTAS 500G", "7891000200018", "12.00", "20.00"),
                new Grocery("ARROZ AVISO OFERTAS 5KG", "7891000200025", "18.00", "30.00"),
                new Grocery("OLEO AVISO OFERTAS 900ML", "7891000200032", "5.00", "9.00"));
        var now = BrazilClock.nowDateTime();
        buy(shopper, CHEAPER_MARKET, now.minusDays(4), announcedGroceries, true);
        // A price is only shown once enough households paid it enough times.
        var firstNeighbour = newShopper();
        var secondNeighbour = newShopper();
        for (var neighbour : List.of(firstNeighbour, secondNeighbour)) {
            buy(neighbour, CHEAPER_MARKET, now.minusDays(6), announcedGroceries, true);
            buy(neighbour, CHEAPER_MARKET, now.minusDays(5), announcedGroceries, true);
        }
        buy(shopper, DEARER_MARKET, now.minusDays(2), announcedGroceries, false);
        placeTestMarketsInTheShoppersState();
        assertEquals(3, offersOf(shopper, "").size(), "each grocery is an offer at the cheaper market");

        var recipient = userRepository.findByEmail(shopper.email()).orElseThrow();
        assertTrue(dealsDigestScheduler.processUser(recipient, now.atZone(BrazilClock.ZONE).toOffsetDateTime()),
                "the digest announces the offers");
        var notice = onlyOffersNotice(shopper);
        assertEquals("DEALS", notice.get("destination").asText());
        var announced = offerKeys(offersOf(shopper, ""));

        // An offer that shows up after the notice: on the full list, not among the notice's.
        var laterGrocery = List.of(new Grocery("ACUCAR AVISO OFERTAS 1KG", "7891000200049", "3.00", "6.00"));
        buy(shopper, CHEAPER_MARKET, now.minusHours(5), laterGrocery, true);
        for (var neighbour : List.of(firstNeighbour, secondNeighbour)) {
            buy(neighbour, CHEAPER_MARKET, now.minusDays(2), laterGrocery, true);
            buy(neighbour, CHEAPER_MARKET, now.minusDays(1), laterGrocery, true);
        }
        buy(shopper, DEARER_MARKET, now.minusHours(2), laterGrocery, false);
        placeTestMarketsInTheShoppersState();

        assertEquals(4, offersOf(shopper, "").size());
        var fromNotice = offersOf(shopper, "?notificationId=" + notice.get("id").asText());
        assertEquals(announced, offerKeys(fromNotice));
        assertEquals(3, fromNotice.size());
        assertEquals(announced, offerKeys(offersOf(shopper, "?limit=1&notificationId=" + notice.get("id").asText())),
                "the caller's paging never cuts an announced offer off");

        var stranger = newShopper();
        assertEquals(0, offersOf(stranger, "?notificationId=" + notice.get("id").asText()).size(),
                "someone else's notice opens nothing");
        assertEquals(0, offersOf(shopper, "?notificationId=" + UUID.randomUUID()).size());
    }

    private record Shopper(String token, UUID userId, String email) {}

    private record Market(String cnpj, String name, String chavePrefix) {}

    private record Grocery(String description, String ean, String cheaperPrice, String dearerPrice) {}

    private Shopper newShopper() throws Exception {
        var email = "notice-deals-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        var body = """
                { "name": "Notice Deals", "email": "%s", "password": "password123",
                  "acceptedTermsVersion": "1.1", "acceptedPrivacyVersion": "1.1" }
                """.formatted(email);
        var registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var token = objectMapper.readTree(registered.getResponse().getContentAsString()).get("token").asText();
        var userId = userRepository.findByEmail(email).orElseThrow().getId();
        testUserIds.add(userId);
        return new Shopper(token, userId, email);
    }

    private void buy(Shopper shopper, Market market, LocalDateTime issuedAt, List<Grocery> groceries,
                     boolean atTheCheaperPrice) throws Exception {
        var chave = market.chavePrefix() + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        var lines = new ArrayList<ParsedReceiptItem>();
        var total = BigDecimal.ZERO;
        for (var grocery : groceries) {
            var unitPrice = new BigDecimal(atTheCheaperPrice ? grocery.cheaperPrice() : grocery.dearerPrice());
            lines.add(ParsedReceiptItem.builder()
                    .lineNumber(lines.size() + 1)
                    .rawDescription(grocery.description()).ean(grocery.ean())
                    .quantity(BigDecimal.ONE).unit("UN")
                    .unitPrice(unitPrice).totalPrice(unitPrice)
                    .build());
            total = total.add(unitPrice);
        }
        when(sefazIngestionService.resolveChave(any())).thenReturn(chave);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(new SefazIngestionService.FetchedDocument(
                null, "<html/>", "00000000000000000000000000000000000000000000", UnidadeFederativa.RS, null));
        when(sefazIngestionService.parse(any(), any(), any())).thenReturn(ParsedReceipt.builder()
                .chaveAcesso(chave).cnpjEmitente(market.cnpj()).marketName(market.name())
                .issuedAt(issuedAt).totalAmount(total).sourceUrl(null).rawHtml("<html/>")
                .items(lines)
                .build());

        var submitted = mockMvc.perform(post("/api/v1/receipts")
                        .header("Authorization", "Bearer " + shopper.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=" + chave + "|2|1\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var receiptId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("id").asText();
        receiptIngestionService.ingest(UUID.fromString(receiptId), chave);
        mockMvc.perform(post("/api/v1/receipts/" + receiptId + "/confirm")
                        .header("Authorization", "Bearer " + shopper.token()))
                .andExpect(status().isOk());
    }

    /**
     * The mocked portal carries no store address, so the prices come without a state; offers are
     * only looked for in the shopper's own state.
     */
    private void placeTestMarketsInTheShoppersState() {
        var testMarkets = Set.of(CHEAPER_MARKET.cnpj(), DEARER_MARKET.cnpj());
        var unplaced = observationRepository.findAll().stream()
                .filter(observation -> testMarkets.contains(observation.getMarketCnpj()))
                .filter(observation -> observation.getState() == null)
                .toList();
        unplaced.forEach(observation -> observation.setState(UnidadeFederativa.RS.name()));
        observationRepository.saveAll(unplaced);
    }

    private JsonNode offersOf(Shopper shopper, String query) throws Exception {
        var separator = query.isEmpty() ? "?" : "&";
        return getJson(shopper, "/api/v1/deals" + query + separator + "includeNearby=true");
    }

    private Set<String> offerKeys(JsonNode offers) {
        var keys = new HashSet<String>();
        for (var offer : offers) {
            assertEquals(CHEAPER_MARKET.cnpj(), offer.get("marketCnpj").asText());
            keys.add(offer.get("productId").asText());
        }
        return keys;
    }

    private JsonNode onlyOffersNotice(Shopper shopper) throws Exception {
        var notices = new ArrayList<JsonNode>();
        for (var notice : getJson(shopper, "/api/v1/notifications?page=0&size=20").get("content")) {
            if ("DEALS_DIGEST".equals(notice.get("type").asText())) notices.add(notice);
        }
        assertEquals(1, notices.size(), "one offers notice was sent");
        return notices.get(0);
    }

    private JsonNode getJson(Shopper shopper, String path) throws Exception {
        var response = mockMvc.perform(get(path).header("Authorization", "Bearer " + shopper.token()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString());
    }
}
