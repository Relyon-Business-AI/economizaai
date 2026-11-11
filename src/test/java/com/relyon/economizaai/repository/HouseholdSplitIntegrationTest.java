package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdMarketAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.ShoppingList;
import com.relyon.economizaai.model.ShoppingListItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.HouseholdSplitService;
import com.relyon.economizaai.service.ReceiptRawHtmlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy/absorb against a real database (H2): unique constraints, cascades and the
 * delete of an absorbed household are exactly what mocks can't prove.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({HouseholdSplitService.class, ReceiptRawHtmlService.class})
class HouseholdSplitIntegrationTest {

    @Autowired private HouseholdSplitService splitService;
    @Autowired private ReceiptRawHtmlService receiptRawHtmlService;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ShoppingListRepository shoppingListRepository;
    @Autowired private HouseholdCustomCategoryRepository customCategoryRepository;
    @Autowired private HouseholdMarketAliasRepository marketAliasRepository;
    @Autowired private TestEntityManager entityManager;

    private Household shared;
    private Household home;
    private User host;
    private User guest;

    @BeforeEach
    void setUp() {
        shared = householdRepository.save(Household.builder().inviteCode("SHRD01").build());
        home = householdRepository.save(Household.builder().inviteCode("HOME01").build());
        host = saveUser("host-split@economizaai.app", shared);
        guest = saveUser("guest-split@economizaai.app", shared);
    }

    private User saveUser(String email, Household household) {
        return userRepository.save(User.builder()
                .name(email).email(email).password("x").household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Receipt seedReceipt(User scanner, Household household, String chave, ReceiptStatus status) {
        var receipt = Receipt.builder()
                .user(scanner).household(household).originHousehold(household)
                .chaveAcesso(chave).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(status)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("ARROZ 5KG").quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("10.00")).totalPrice(new BigDecimal("10.00"))
                .paidTotalPrice(new BigDecimal("8.00"))
                .build());
        return receiptRepository.save(receipt);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- copy ----------

    @Test
    void copyInto_everything_duplicatesFinishedReceiptsWithItemsOutOfTheIndex() {
        seedReceipt(host, shared, "CH1", ReceiptStatus.CONFIRMED);
        seedReceipt(guest, shared, "CH2", ReceiptStatus.PENDING_CONFIRMATION);
        seedReceipt(guest, shared, "INFLIGHT", ReceiptStatus.PROCESSING);

        var result = splitService.copyInto(shared, home, null);
        flushAndClear();

        assertEquals(2, result.receipts());
        var copies = receiptRepository.findAllByHouseholdId(home.getId());
        assertEquals(2, copies.size());
        copies.forEach(copy -> {
            assertFalse(copy.isIndexEligible(), "a copy must never feed the price index again");
            assertEquals(home.getId(), copy.getOriginHousehold().getId());
            assertEquals(1, copy.getItems().size());
            assertEquals(0, new BigDecimal("8.00").compareTo(copy.getItems().get(0).getPaidTotalPrice()));
        });
        assertEquals(3, receiptRepository.findAllByHouseholdId(shared.getId()).size(), "originals stay put");
    }

    @Test
    void copyInto_carriesTheStoredPortalDocumentToTheCopy() {
        var withDocument = seedReceipt(host, shared, "DOC1", ReceiptStatus.CONFIRMED);
        seedReceipt(host, shared, "NODOC", ReceiptStatus.CONFIRMED);
        receiptRawHtmlService.replace(withDocument, "<html>danfe</html>");
        flushAndClear();

        splitService.copyInto(shared, home, null);
        flushAndClear();

        var copies = receiptRepository.findAllByHouseholdId(home.getId());
        var copyWithDocument = copies.stream()
                .filter(copy -> copy.getChaveAcesso().equals("DOC1")).findFirst().orElseThrow();
        var copyWithoutDocument = copies.stream()
                .filter(copy -> copy.getChaveAcesso().equals("NODOC")).findFirst().orElseThrow();
        assertNotEquals(withDocument.getId(), copyWithDocument.getId());
        assertEquals("<html>danfe</html>", receiptRawHtmlService.find(copyWithDocument.getId()).orElseThrow());
        assertTrue(receiptRawHtmlService.find(copyWithoutDocument.getId()).isEmpty());
        assertEquals("<html>danfe</html>", receiptRawHtmlService.find(withDocument.getId()).orElseThrow(),
                "the original keeps its document");
    }

    @Test
    void copyInto_skipsReceiptsTheDestinationAlreadyHas() {
        seedReceipt(host, shared, "SAME", ReceiptStatus.CONFIRMED);
        seedReceipt(guest, home, "SAME", ReceiptStatus.CONFIRMED);

        var result = splitService.copyInto(shared, home, null);
        flushAndClear();

        assertEquals(0, result.receipts());
        assertEquals(1, receiptRepository.findAllByHouseholdId(home.getId()).size());
    }

    @Test
    void copyInto_sinceJoin_takesOnlyWhatWasCreatedAfter() throws InterruptedException {
        seedReceipt(host, shared, "BEFORE", ReceiptStatus.CONFIRMED);
        flushAndClear();
        Thread.sleep(20);
        var joinedAt = LocalDateTime.now();
        Thread.sleep(20);
        seedReceipt(host, shared, "AFTER", ReceiptStatus.CONFIRMED);

        splitService.copyInto(shared, home, joinedAt);
        flushAndClear();

        var copies = receiptRepository.findAllByHouseholdId(home.getId());
        assertEquals(1, copies.size());
        assertEquals("AFTER", copies.get(0).getChaveAcesso());
    }

    @Test
    void copyInto_copiesListsAndCustomCategoriesKeepingTheParentChain() {
        var list = ShoppingList.builder().household(shared).createdBy(host).name("Feira").build();
        list.addItem(ShoppingListItem.builder().freeText("banana").build());
        shoppingListRepository.save(list);
        var parent = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(shared).name("Bebê").build());
        customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(shared).name("Fraldas").parentCustomCategory(parent).build());
        marketAliasRepository.save(HouseholdMarketAlias.builder()
                .household(shared).marketCnpj("12345678000190").customName("Zaffari de casa").build());

        splitService.copyInto(shared, home, null);
        flushAndClear();

        var lists = shoppingListRepository.findAllByHouseholdId(home.getId());
        assertEquals(1, lists.size());
        assertEquals(1, lists.get(0).getItems().size());
        var categories = customCategoryRepository.findAllByHouseholdId(home.getId());
        assertEquals(2, categories.size());
        var child = categories.stream().filter(category -> category.getName().equals("Fraldas")).findFirst().orElseThrow();
        assertEquals("Bebê", child.getParentCustomCategory().getName());
        assertEquals(home.getId(), child.getParentCustomCategory().getHousehold().getId());
        assertEquals(1, marketAliasRepository.findAllByHouseholdId(home.getId()).size());
        assertEquals(2, customCategoryRepository.findAllByHouseholdId(shared.getId()).size(), "originals stay put");
    }

    // ---------- absorb ----------

    @Test
    void absorbInto_movesEverythingDropsDuplicatesAndDeletesTheHousehold() {
        seedReceipt(host, shared, "ONLY_SHARED", ReceiptStatus.CONFIRMED);
        seedReceipt(host, shared, "BOTH", ReceiptStatus.CONFIRMED);
        var homeCopy = seedReceipt(guest, home, "BOTH", ReceiptStatus.CONFIRMED);
        var sharedCategory = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(shared).name("Bebê").build());
        customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(shared).name("Fraldas").parentCustomCategory(sharedCategory).build());
        var homeCategory = customCategoryRepository.save(HouseholdCustomCategory.builder()
                .household(home).name("Bebê").build());
        guest.setHousehold(home);
        host.setHousehold(home);
        userRepository.save(guest);
        userRepository.save(host);
        flushAndClear();

        var sharedHousehold = householdRepository.findById(shared.getId()).orElseThrow();
        var homeHousehold = householdRepository.findById(home.getId()).orElseThrow();
        splitService.absorbInto(sharedHousehold, homeHousehold);
        flushAndClear();

        assertFalse(householdRepository.existsById(shared.getId()));
        var receipts = receiptRepository.findAllByHouseholdId(home.getId());
        assertEquals(2, receipts.size());
        assertTrue(receipts.stream().anyMatch(receipt -> receipt.getId().equals(homeCopy.getId())),
                "on a collision the home's receipt survives");
        receipts.forEach(receipt -> assertEquals(home.getId(), receipt.getOriginHousehold().getId()));
        var categories = customCategoryRepository.findAllByHouseholdId(home.getId());
        assertEquals(2, categories.size());
        var child = categories.stream().filter(category -> category.getName().equals("Fraldas")).findFirst().orElseThrow();
        assertEquals(homeCategory.getId(), child.getParentCustomCategory().getId(),
                "a child of a dropped duplicate now hangs under the home's same-name category");
        assertNotEquals(sharedCategory.getId(), child.getParentCustomCategory().getId());
    }
}
