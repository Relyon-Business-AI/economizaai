package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.ReceiptResponse;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dev-only test-data seeding for QA / e2e flows. Not for prod — the caller
 * ({@code AdminController}) guards every entry point on
 * {@code economizaai.admin.dev-seed-enabled}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDevService {

    /** Marks seeded receipts so a re-seed can find and replace earlier ones. */
    static final String SEED_QR_PAYLOAD = "seed://discounted-receipt";

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;

    /**
     * Plants a PENDING_CONFIRMATION receipt with a receipt-level discount and
     * three lines carrying a manual paid price (promotional), so the
     * review/discount UI has deterministic promotions to exercise. Line/market
     * names look like a real NFC-e (screenshot/marketing friendly). Seeds into
     * {@code targetEmail}'s account when given (admin planting screenshot data
     * for another account), the caller's own otherwise. Earlier seeded receipts
     * of the target household are REPLACED, so re-seeding never piles up.
     * Returns the created receipt.
     */
    @Transactional
    public ReceiptResponse seedDiscountedReceipt(User caller, String targetEmail) {
        var target = resolveTarget(caller, targetEmail);
        replaceEarlierSeeds(target);
        var receipt = Receipt.builder()
                .user(target)
                .household(target.getHousehold())
                .chaveAcesso(randomChave())
                .qrPayload(SEED_QR_PAYLOAD)
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190")
                .marketName("SUPERMERCADO NACIONAL")
                .issuedAt(LocalDateTime.now())
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .discountTotal(new BigDecimal("5.00"))
                // gross 91.52 − discount 5.00 = 86.52 net "valor a pagar"
                .totalAmount(new BigDecimal("86.52"))
                .build();

        // Lines 1-3 were bought on promotion: paid total < as-printed total.
        receipt.addItem(item(1, "ARROZ BRANCO TIO JOAO T1 5KG", "1", "25.90", "25.90", "19.90"));
        receipt.addItem(item(2, "FEIJAO PRETO CAMIL 1KG", "2", "8.99", "17.98", "14.98"));
        receipt.addItem(item(3, "CAFE PILAO TORR MOIDO 500G", "1", "18.90", "18.90", "14.90"));
        receipt.addItem(item(4, "LEITE UHT INT ITALAC 1L", "6", "4.79", "28.74", null));

        var saved = receiptRepository.save(receipt);
        log.info("dev.seed.discounted_receipt caller={} target={} receipt={} items={} discount={}",
                caller.getEmail(), target.getEmail(), saved.getId(), saved.getItems().size(),
                saved.getDiscountTotal());
        return ReceiptResponse.from(saved);
    }

    private void replaceEarlierSeeds(User target) {
        var earlierSeeds = receiptRepository.findAllByHouseholdIdAndQrPayload(
                target.getHousehold().getId(), SEED_QR_PAYLOAD);
        if (earlierSeeds.isEmpty()) return;
        receiptRepository.deleteAll(earlierSeeds);
        log.info("dev.seed.replaced_earlier target={} removed={}", target.getEmail(), earlierSeeds.size());
    }

    private User resolveTarget(User caller, String targetEmail) {
        if (targetEmail == null || targetEmail.isBlank()) return caller;
        var email = targetEmail.trim();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    private ReceiptItem item(int lineNumber, String description, String quantity,
                             String unitPrice, String totalPrice, String paidTotalPrice) {
        var quantityValue = new BigDecimal(quantity);
        var paidTotal = paidTotalPrice == null ? null : new BigDecimal(paidTotalPrice);
        var paidUnit = paidTotal == null || quantityValue.signum() == 0
                ? null : paidTotal.divide(quantityValue, 4, RoundingMode.HALF_UP);
        return ReceiptItem.builder()
                .lineNumber(lineNumber)
                .rawDescription(description)
                .quantity(quantityValue)
                .unit("UN")
                .unitPrice(new BigDecimal(unitPrice))
                .totalPrice(new BigDecimal(totalPrice))
                .paidUnitPrice(paidUnit)
                .paidTotalPrice(paidTotal)
                .build();
    }

    private String randomChave() {
        var random = ThreadLocalRandom.current();
        var chave = new StringBuilder(44);
        for (var digit = 0; digit < 44; digit++) {
            chave.append(random.nextInt(10));
        }
        return chave.toString();
    }
}
