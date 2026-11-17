package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.PriceExposureRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.exposure.PriceExposureService;
import com.relyon.economizaai.service.exposure.ShownPrice;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "Com a ajuda do app" number from end to end, through the real endpoints and the real
 * database: confirm receipts, show a price, buy cheaper, buy again, pay the old price, delete,
 * scan again — and read the total the screen shows after each step.
 *
 * <p>Only the government portal fetch is mocked. NOT {@code @Transactional}: the attribution runs
 * in its own transaction and only sees receipts that were really committed, as in production. The
 * CNPJ registry lookup is turned off so the test never leaves the machine, and the test accounts
 * opt out of the shared price index so nothing leaks into other tests' counts.
 */
@SpringBootTest(webEnvironment = MOCK, properties = "economizaai.merchant.classify.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AppSavingsFlowIntegrationTest {

    private static final String MARKET_CNPJ = "93015006005182";
    private static final String CHAVE_PREFIX = "432604930150060051826511300033940214";

    @Autowired private MockMvc mockMvc;
    @Autowired private ReceiptIngestionService receiptIngestionService;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private PriceExposureRepository priceExposureRepository;
    @Autowired private DealSurfaceStateRepository surfaceStateRepository;
    @Autowired private PriceExposureService priceExposureService;

    @MockitoBean private SefazIngestionService sefazIngestionService;

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
    }

    @Test
    void shownPrice_thenCheaperPurchases_countForTheHouseholdAndNeverGoDown() throws Exception {
        var shopper = newShopper();
        var rice = new Grocery("ARROZ INTEGRAL SAVINGS 5KG", "7891000100011");
        var now = BrazilClock.nowDateTime();

        // Bought at 10.00 before the app showed anything: nothing saved with the app.
        var boughtBeforeTheApp = confirmPurchase(shopper, now.minusDays(20), line(rice, "10.00", "1"));
        assertSavedWithTheApp(shopper, "0", 0);

        // The app shows 8.00 at that market, two days ago.
        var productId = productOf(boughtBeforeTheApp);
        var shownPrice = showPrice(shopper, productId, "8.00", PriceExposureSurface.BEST_MARKETS, now.minusDays(2));

        // First cheaper purchase: (10.00 - 8.00) x 2.
        var firstCheaperPurchase = confirmPurchase(shopper, now.minusDays(1), line(rice, "8.00", "2"));
        assertSavedWithTheApp(shopper, "4.00", 1);
        assertNotNull(priceExposureRepository.findById(shownPrice).orElseThrow().getConvertedAt());
        var firstCredit = onlyPurchaseShown(shopper);
        assertEquals("BEST_MARKETS", firstCredit.get("source").asText());
        assertMoney("8.00", firstCredit.get("paidPrice"));
        assertMoney("10.00", firstCredit.get("previousPrice"));
        assertMoney("4.00", firstCredit.get("saved"));
        assertEquals(productId.toString(), firstCredit.get("productId").asText());

        // Buying again at the cheaper price keeps counting against the 10.00 of before the app.
        var boughtAgain = confirmPurchase(shopper, now.minusHours(12), line(rice, "8.00", "1"));
        assertSavedWithTheApp(shopper, "6.00", 2);

        // Back at the old price, and above it: counts nothing and takes nothing away.
        confirmPurchase(shopper, now.minusHours(6), line(rice, "10.00", "3"));
        confirmPurchase(shopper, now.minusHours(5), line(rice, "11.90", "1"));
        assertSavedWithTheApp(shopper, "6.00", 2);

        // Cheaper than what the app showed: the credit stops at the advantage shown (10.00 - 8.00).
        confirmPurchase(shopper, now.minusHours(3), line(rice, "7.00", "1"));
        assertSavedWithTheApp(shopper, "8.00", 3);

        // What was written is what a later purchase reads back.
        var conversions = conversionsOf(shopper);
        assertEquals(3, conversions.size());
        assertEquals(1, conversions.stream().filter(conversion -> !metadataOf(conversion).path("followUp").asBoolean()).count());
        for (var conversion : conversions) {
            var metadata = metadataOf(conversion);
            assertEquals(0, new BigDecimal("10.00").compareTo(metadata.get("previousLastPaid").decimalValue()));
            assertEquals(0, new BigDecimal("8.00").compareTo(metadata.get("shownUnitPrice").decimalValue()));
            assertTrue(metadata.hasNonNull("receiptItemId"));
            assertTrue(metadata.hasNonNull("purchasedAt"));
        }

        // Deleting a receipt takes its saving out of the total.
        deleteReceipt(shopper, boughtAgain.receiptId());
        assertSavedWithTheApp(shopper, "6.00", 2);

        // Deleting the first cheaper purchase frees the shown price...
        deleteReceipt(shopper, firstCheaperPurchase.receiptId());
        assertSavedWithTheApp(shopper, "2.00", 1);
        assertNull(priceExposureRepository.findById(shownPrice).orElseThrow().getConvertedAt());

        // ...so scanning that same receipt again counts it once, not twice.
        confirmPurchase(shopper, now.minusDays(1), firstCheaperPurchase.chave(), line(rice, "8.00", "2"));
        assertSavedWithTheApp(shopper, "6.00", 2);
    }

    @Test
    void twoLinesOfTheSameProductOnOneReceipt_bothCountAgainstThePriceOfBeforeTheApp() throws Exception {
        var shopper = newShopper();
        var coffee = new Grocery("CAFE TORRADO SAVINGS 500G", "7891000100028");
        var now = BrazilClock.nowDateTime();

        var boughtBeforeTheApp = confirmPurchase(shopper, now.minusDays(15), line(coffee, "20.00", "1"));
        showPrice(shopper, productOf(boughtBeforeTheApp), "17.00", PriceExposureSurface.MARKET_PRICES, now.minusDays(3));

        confirmPurchase(shopper, now.minusDays(1), line(coffee, "17.00", "1"), line(coffee, "17.00", "2"));

        // (20.00 - 17.00) x 1 + (20.00 - 17.00) x 2
        assertSavedWithTheApp(shopper, "9.00", 2);
    }

    @Test
    void surfacedDeal_isCreditedAndLaterPurchasesFollowWithoutACap() throws Exception {
        var shopper = newShopper();
        var beans = new Grocery("FEIJAO PRETO SAVINGS 1KG", "7891000100035");
        var now = BrazilClock.nowDateTime();

        var boughtBeforeTheApp = confirmPurchase(shopper, now.minusDays(25), line(beans, "9.00", "1"));
        var surfacedDeal = surfaceStateRepository.save(DealSurfaceState.builder()
                .userId(shopper.userId()).productId(productOf(boughtBeforeTheApp)).marketCnpj(MARKET_CNPJ)
                .lastUnitPrice(new BigDecimal("9.00")).lastDiscountFraction(new BigDecimal("0.2000"))
                .lastSurfacedAt(now.minusDays(2).atZone(BrazilClock.ZONE).toOffsetDateTime())
                .build());

        var dealPurchase = confirmPurchase(shopper, now.minusDays(1), line(beans, "7.20", "1"));
        assertSavedWithTheApp(shopper, "1.80", 1);
        assertEquals("DEALS", onlyPurchaseShown(shopper).get("source").asText());
        assertNotNull(surfaceStateRepository.findById(surfacedDeal.getId()).orElseThrow().getConvertedAt());

        confirmPurchase(shopper, now.minusHours(4), line(beans, "6.00", "2"));
        // 1.80 + (9.00 - 6.00) x 2
        assertSavedWithTheApp(shopper, "7.80", 2);

        deleteReceipt(shopper, dealPurchase.receiptId());
        assertSavedWithTheApp(shopper, "6.00", 1);
        assertNull(surfaceStateRepository.findById(surfacedDeal.getId()).orElseThrow().getConvertedAt());
    }

    @Test
    void priceShownAfterThePurchase_orToAnotherHousehold_earnsNothing() throws Exception {
        var shopper = newShopper();
        var neighbour = newShopper();
        var milk = new Grocery("LEITE INTEGRAL SAVINGS 1L", "7891000100042");
        var now = BrazilClock.nowDateTime();

        var boughtBeforeTheApp = confirmPurchase(shopper, now.minusDays(10), line(milk, "6.00", "1"));
        var productId = productOf(boughtBeforeTheApp);
        // The neighbour saw the cheaper price in time; the shopper only saw it after buying.
        showPrice(neighbour, productId, "5.00", PriceExposureSurface.BEST_MARKETS, now.minusDays(3));
        showPrice(shopper, productId, "5.00", PriceExposureSurface.BEST_MARKETS, now.minusHours(1));

        confirmPurchase(shopper, now.minusDays(1), line(milk, "5.00", "4"));

        assertSavedWithTheApp(shopper, "0", 0);
        assertSavedWithTheApp(neighbour, "0", 0);
    }

    @Test
    void neverBoughtBefore_aShownPriceEarnsNothing() throws Exception {
        var shopper = newShopper();
        var soap = new Grocery("SABAO EM PO SAVINGS 1KG", "7891000100059");
        var otherProduct = new Grocery("AMACIANTE SAVINGS 2L", "7891000100066");
        var now = BrazilClock.nowDateTime();

        // A confirmed receipt only to obtain the product the catalog creates for the soap.
        var neighbour = newShopper();
        var neighbourPurchase = confirmPurchase(neighbour, now.minusDays(30), line(soap, "15.00", "1"));
        showPrice(shopper, productOf(neighbourPurchase), "12.00", PriceExposureSurface.DEALS, now.minusDays(2));

        confirmPurchase(shopper, now.minusDays(1), line(soap, "12.00", "1"), line(otherProduct, "9.00", "1"));

        assertSavedWithTheApp(shopper, "0", 0);
    }

    private record Shopper(String token, UUID userId, String email) {}

    private record Grocery(String description, String ean) {}

    private record Purchase(String receiptId, String chave) {}

    private Shopper newShopper() throws Exception {
        var email = "savings-flow-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        var body = """
                { "name": "Savings Flow", "email": "%s", "password": "password123",
                  "acceptedTermsVersion": "1.1", "acceptedPrivacyVersion": "1.1" }
                """.formatted(email);
        var registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var token = objectMapper.readTree(registered.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(patch("/api/v1/users/me/contribution")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contributionOptIn\":false}"))
                .andExpect(status().isOk());
        var userId = userRepository.findByEmail(email).orElseThrow().getId();
        testUserIds.add(userId);
        return new Shopper(token, userId, email);
    }

    private record PurchasedLine(Grocery grocery, BigDecimal unitPrice, BigDecimal quantity) {
        BigDecimal total() {
            return unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static PurchasedLine line(Grocery grocery, String unitPrice, String quantity) {
        return new PurchasedLine(grocery, new BigDecimal(unitPrice), new BigDecimal(quantity));
    }

    private Purchase confirmPurchase(Shopper shopper, LocalDateTime issuedAt, PurchasedLine... lines) throws Exception {
        var chave = CHAVE_PREFIX + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        return confirmPurchase(shopper, issuedAt, chave, lines);
    }

    private Purchase confirmPurchase(Shopper shopper, LocalDateTime issuedAt, String chave,
                                     PurchasedLine... lines) throws Exception {
        var numberedLines = new ArrayList<ParsedReceiptItem>();
        var total = BigDecimal.ZERO;
        for (var line : lines) {
            numberedLines.add(ParsedReceiptItem.builder()
                    .lineNumber(numberedLines.size() + 1)
                    .rawDescription(line.grocery().description()).ean(line.grocery().ean())
                    .quantity(line.quantity()).unit("UN")
                    .unitPrice(line.unitPrice()).totalPrice(line.total())
                    .build());
            total = total.add(line.total());
        }
        when(sefazIngestionService.resolveChave(any())).thenReturn(chave);
        when(sefazIngestionService.fetch(any(), any())).thenReturn(new SefazIngestionService.FetchedDocument(
                null, "<html/>", "00000000000000000000000000000000000000000000", UnidadeFederativa.RS, null));
        when(sefazIngestionService.parse(any(), any(), any())).thenReturn(ParsedReceipt.builder()
                .chaveAcesso(chave).cnpjEmitente(MARKET_CNPJ).marketName("Mercado Savings Flow")
                .issuedAt(issuedAt).totalAmount(total).sourceUrl(null).rawHtml("<html/>")
                .items(numberedLines)
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
        return new Purchase(receiptId, chave);
    }

    private void deleteReceipt(Shopper shopper, String receiptId) throws Exception {
        mockMvc.perform(delete("/api/v1/receipts/" + receiptId)
                        .header("Authorization", "Bearer " + shopper.token()))
                .andExpect(status().is2xxSuccessful());
    }

    private UUID productOf(Purchase purchase) {
        var receipt = receiptRepository.findByIdWithItemsAndProducts(UUID.fromString(purchase.receiptId())).orElseThrow();
        var product = receipt.getItems().get(0).getProduct();
        assertNotNull(product, "the confirmed line must be linked to a catalog product");
        return product.getId();
    }

    /** Logs the price through the same service the screens call, then moves it to when it was "seen". */
    private UUID showPrice(Shopper shopper, UUID productId, String unitPrice, PriceExposureSurface surface,
                           LocalDateTime shownAt) {
        var viewer = userRepository.findByEmail(shopper.email()).orElseThrow();
        priceExposureService.recordShown(viewer, surface,
                List.of(new ShownPrice(productId, MARKET_CNPJ, new BigDecimal(unitPrice))));
        var exposure = exposureOf(viewer, productId, surface);
        exposure.setShownAt(shownAt.atZone(BrazilClock.ZONE).toOffsetDateTime());
        exposure.setShownOn(shownAt.toLocalDate());
        return priceExposureRepository.save(exposure).getId();
    }

    private PriceExposure exposureOf(User viewer, UUID productId, PriceExposureSurface surface) {
        return priceExposureRepository.findByUserIdAndSurfaceAndShownOn(viewer.getId(), surface, BrazilClock.today()).stream()
                .filter(exposure -> exposure.getProductId().equals(productId))
                .findFirst().orElseThrow(() -> new AssertionError("the shown price was not logged"));
    }

    private void assertSavedWithTheApp(Shopper shopper, String expectedSaved, int expectedPurchases) throws Exception {
        var summary = getJson(shopper, "/api/v1/insights/app-savings");
        assertMoney(expectedSaved, summary.get("saved"));
        assertEquals(expectedPurchases, summary.get("purchaseCount").asInt());
        var purchases = getJson(shopper, "/api/v1/insights/app-savings/purchases?page=0&size=50");
        assertEquals(expectedPurchases, purchases.get("content").size());
        var listedTotal = BigDecimal.ZERO;
        for (var purchase : purchases.get("content")) {
            listedTotal = listedTotal.add(purchase.get("saved").decimalValue());
            assertFalse(purchase.get("saved").decimalValue().signum() <= 0, "a listed purchase always saved something");
        }
        assertEquals(0, new BigDecimal(expectedSaved).compareTo(listedTotal), "the list adds up to the total");
    }

    private JsonNode onlyPurchaseShown(Shopper shopper) throws Exception {
        var purchases = getJson(shopper, "/api/v1/insights/app-savings/purchases?page=0&size=50").get("content");
        assertEquals(1, purchases.size());
        return purchases.get(0);
    }

    private JsonNode getJson(Shopper shopper, String path) throws Exception {
        var response = mockMvc.perform(get(path).header("Authorization", "Bearer " + shopper.token()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString());
    }

    private List<NotificationEvent> conversionsOf(Shopper shopper) {
        return eventRepository.findAllByUserId(shopper.userId()).stream()
                .filter(event -> event.getEventType() == NotificationEventType.CONVERTED)
                .toList();
    }

    private JsonNode metadataOf(NotificationEvent conversion) {
        try {
            return objectMapper.readTree(conversion.getMetadata());
        } catch (Exception unreadableMetadata) {
            throw new AssertionError("conversion metadata must be readable JSON", unreadableMetadata);
        }
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual);
    }
}
