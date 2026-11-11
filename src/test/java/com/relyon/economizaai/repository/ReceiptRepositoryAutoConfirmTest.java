package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The auto-confirm sweeper only sees notas it can actually confirm, and only a capped page of them. */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptRepositoryAutoConfirmTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, Month.MAY, 1, 12, 0);

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User user;
    private LocalDateTime cutoffAfterEverything;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("AUTO01").build());
        user = userRepository.save(User.builder()
                .name("Tester").email("auto-confirm-repo@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
        cutoffAfterEverything = LocalDateTime.now().plusDays(1);
    }

    @Test
    void findAutoConfirmableIds_returnsPendingScansAndCompletePhotos_skipsIncompletePhotosAndOtherStatuses() {
        var pendingScan = save(receipt(ReceiptOrigin.SCAN, ReceiptStatus.PENDING_CONFIRMATION, ISSUED_AT, "Mercado A",
                item("ARROZ", new BigDecimal("10.00"), false)));
        var completePhoto = save(receipt(ReceiptOrigin.PHOTO, ReceiptStatus.PENDING_CONFIRMATION, ISSUED_AT, "Mercado A",
                item("FEIJAO", new BigDecimal("8.00"), false),
                item(" ", new BigDecimal("1.00"), true))); // excluded lines don't count
        save(receipt(ReceiptOrigin.PHOTO, ReceiptStatus.PENDING_CONFIRMATION, null, "Mercado A",
                item("SEM DATA", new BigDecimal("8.00"), false)));
        save(receipt(ReceiptOrigin.PHOTO, ReceiptStatus.PENDING_CONFIRMATION, ISSUED_AT, " ",
                item("SEM MERCADO", new BigDecimal("8.00"), false)));
        // (an unpriced line can't be staged here: the test schema keeps total_price NOT NULL)
        save(receipt(ReceiptOrigin.PHOTO, ReceiptStatus.PENDING_CONFIRMATION, ISSUED_AT, "Mercado A",
                item(" ", new BigDecimal("8.00"), false)));
        save(receipt(ReceiptOrigin.SCAN, ReceiptStatus.CONFIRMED, ISSUED_AT, "Mercado A",
                item("JA CONFIRMADA", new BigDecimal("8.00"), false)));

        var ids = receiptRepository.findAutoConfirmableIds(cutoffAfterEverything, PageRequest.of(0, 50));

        assertThat(ids).containsExactlyInAnyOrder(pendingScan, completePhoto);
    }

    @Test
    void findAutoConfirmableIds_respectsTheCutoffAndThePageCap() {
        for (var index = 0; index < 3; index++) {
            save(receipt(ReceiptOrigin.SCAN, ReceiptStatus.PENDING_CONFIRMATION, ISSUED_AT, "Mercado A",
                    item("ITEM " + index, new BigDecimal("1.00"), false)));
        }

        assertThat(receiptRepository.findAutoConfirmableIds(cutoffAfterEverything, PageRequest.of(0, 2))).hasSize(2);
        assertThat(receiptRepository.findAutoConfirmableIds(LocalDateTime.now().minusDays(1), PageRequest.of(0, 50)))
                .isEmpty();
    }

    private UUID save(Receipt receipt) {
        return receiptRepository.saveAndFlush(receipt).getId();
    }

    private Receipt receipt(ReceiptOrigin origin, ReceiptStatus status, LocalDateTime issuedAt, String marketName,
                            ReceiptItem... items) {
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName(marketName)
                .issuedAt(issuedAt).totalAmount(BigDecimal.TEN)
                .qrPayload("payload").origin(origin).status(status)
                .build();
        var lineNumber = 1;
        for (var item : items) {
            item.setLineNumber(lineNumber++);
            receipt.addItem(item);
        }
        return receipt;
    }

    private static ReceiptItem item(String description, BigDecimal totalPrice, boolean excluded) {
        return ReceiptItem.builder()
                .rawDescription(description)
                .quantity(BigDecimal.ONE).unitPrice(totalPrice).totalPrice(totalPrice)
                .excluded(excluded)
                .build();
    }
}
