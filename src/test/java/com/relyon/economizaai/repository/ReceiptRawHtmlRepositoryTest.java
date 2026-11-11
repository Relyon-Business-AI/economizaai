package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.ReceiptRawHtml;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.ReceiptRawHtmlService;
import jakarta.persistence.metamodel.Attribute;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portal document of a receipt lives in its own table: the {@link Receipt} entity no
 * longer maps it (so no receipt or receipt-item query can carry it) and it round-trips only
 * through {@link ReceiptRawHtmlService}.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(ReceiptRawHtmlService.class)
class ReceiptRawHtmlRepositoryTest {

    private static final String DANFE_HTML = "<html><body>DANFE</body></html>";

    @Autowired private ReceiptRawHtmlService receiptRawHtmlService;
    @Autowired private ReceiptRawHtmlRepository receiptRawHtmlRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private ReceiptItemRepository receiptItemRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private TestEntityManager entityManager;

    private Household household;
    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        household = householdRepository.save(Household.builder().inviteCode("RAW001").build());
        user = userRepository.save(User.builder()
                .name("Tester").email("test-rawhtml@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
        product = productRepository.save(Product.builder()
                .ean("999").normalizedName("Leite").category(ProductCategory.GROCERIES).build());
    }

    private Receipt seedReceipt(String chave) {
        var receipt = Receipt.builder()
                .user(user).household(household)
                .chaveAcesso(chave).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("4.50"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription("Leite")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("4.50")).totalPrice(new BigDecimal("4.50"))
                .product(product).build());
        return receiptRepository.save(receipt);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void receiptEntity_doesNotMapThePortalDocument() {
        var receiptAttributes = entityManager.getEntityManager().getMetamodel().entity(Receipt.class)
                .getAttributes().stream().map(Attribute::getName).toList();

        assertFalse(receiptAttributes.contains("rawHtml"));
        // The schema Hibernate derives from the mapping has no such column on receipts, so no
        // query that loads a Receipt (directly or via JOIN FETCH from its items) can select it.
        assertEquals(List.of("RECEIPT_RAW_HTML"), tablesWithRawHtmlColumn());
    }

    @SuppressWarnings("unchecked")
    private List<String> tablesWithRawHtmlColumn() {
        return entityManager.getEntityManager().createNativeQuery(
                        "SELECT table_name FROM information_schema.columns WHERE UPPER(column_name) = 'RAW_HTML'")
                .getResultList().stream().map(tableName -> tableName.toString().toUpperCase()).toList();
    }

    @Test
    void itemsHistoryQuery_loadsReceiptsWithoutTouchingTheDocumentTable() {
        var receipt = seedReceipt("CH-HISTORY");
        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        flushAndClear();

        var history = receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), household.getId());

        assertEquals(1, history.size());
        assertEquals("CH-HISTORY", history.get(0).getReceipt().getChaveAcesso());
        var loadedEntityNames = entityManager.getEntityManager().unwrap(Session.class)
                .getStatistics().getEntityKeys().stream()
                .map(entityKey -> ((EntityKey) entityKey).getEntityName()).toList();
        assertTrue(loadedEntityNames.contains(Receipt.class.getName()));
        assertFalse(loadedEntityNames.contains(ReceiptRawHtml.class.getName()),
                "the document must not be loaded along with the receipt");
    }

    @Test
    void replace_storesThenOverwritesTheDocument() {
        var receipt = seedReceipt("CH-REPLACE");

        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        flushAndClear();
        assertEquals(DANFE_HTML, receiptRawHtmlService.find(receipt.getId()).orElseThrow());

        receiptRawHtmlService.replace(receiptRepository.findById(receipt.getId()).orElseThrow(), "<html>rescued</html>");
        flushAndClear();

        assertEquals("<html>rescued</html>", receiptRawHtmlService.find(receipt.getId()).orElseThrow());
        assertEquals(1, receiptRawHtmlRepository.count());
    }

    @Test
    void replace_stripsCpfBeforePersisting() {
        var receipt = seedReceipt("CH-CPF");

        receiptRawHtmlService.replace(receipt, "<p>CPF: 123.456.789-09</p>");
        flushAndClear();

        var stored = receiptRawHtmlService.find(receipt.getId()).orElseThrow();
        assertFalse(stored.contains("123.456.789-09"));
        assertTrue(stored.contains("***.***.***-**"));
    }

    @Test
    void replace_withNullDocument_removesWhatWasStored() {
        var receipt = seedReceipt("CH-NULL");
        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        flushAndClear();

        receiptRawHtmlService.replace(receiptRepository.findById(receipt.getId()).orElseThrow(), null);
        flushAndClear();

        assertTrue(receiptRawHtmlService.find(receipt.getId()).isEmpty());
    }

    @Test
    void find_receiptWithoutDocument_isEmpty() {
        var receipt = seedReceipt("CH-EMPTY");

        assertTrue(receiptRawHtmlService.find(receipt.getId()).isEmpty());
    }

    @Test
    void delete_removesTheDocumentAndIsHarmlessWhenThereIsNone() {
        var receipt = seedReceipt("CH-DELETE");
        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        flushAndClear();

        receiptRawHtmlService.delete(receipt.getId());
        receiptRawHtmlService.delete(receipt.getId());
        flushAndClear();

        assertTrue(receiptRawHtmlService.find(receipt.getId()).isEmpty());
        assertTrue(receiptRepository.existsById(receipt.getId()), "the receipt itself stays");
    }

    @Test
    void copy_givesTheDestinationTheSameDocument() {
        var source = seedReceipt("CH-SOURCE");
        var destination = seedReceipt("CH-DEST");
        receiptRawHtmlService.replace(source, DANFE_HTML);
        flushAndClear();

        receiptRawHtmlService.copy(source.getId(), receiptRepository.findById(destination.getId()).orElseThrow());
        flushAndClear();

        assertEquals(DANFE_HTML, receiptRawHtmlService.find(destination.getId()).orElseThrow());
        assertEquals(DANFE_HTML, receiptRawHtmlService.find(source.getId()).orElseThrow());
    }

    @Test
    void deletingTheReceipt_removesItsDocument() {
        var receipt = seedReceipt("CH-CASCADE");
        var survivor = seedReceipt("CH-SURVIVOR");
        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        receiptRawHtmlService.replace(survivor, DANFE_HTML);
        flushAndClear();

        receiptRepository.delete(receiptRepository.findById(receipt.getId()).orElseThrow());
        flushAndClear();

        assertFalse(receiptRepository.existsById(receipt.getId()));
        assertTrue(receiptRawHtmlService.find(receipt.getId()).isEmpty());
        assertEquals(DANFE_HTML, receiptRawHtmlService.find(survivor.getId()).orElseThrow());
    }

    @Test
    void deletingAllReceiptsOfAHousehold_removesTheirDocuments() {
        var receipt = seedReceipt("CH-ACCOUNT");
        receiptRawHtmlService.replace(receipt, DANFE_HTML);
        flushAndClear();

        receiptRepository.deleteAll(receiptRepository.findAllByHouseholdId(household.getId()));
        flushAndClear();

        assertEquals(0, receiptRawHtmlRepository.count());
    }
}
