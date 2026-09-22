package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import com.relyon.economizaai.exception.PhotoExtractionUnavailableException;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptService;
import com.relyon.economizaai.service.scan.PhotoUploadValidator;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Vision fallback: extracts items from a PHOTOGRAPH of the printed receipt —
 * for contingency notas SEFAZ never received, unreadable QRs, or when typing
 * is not an option. The result is a {@link ReceiptOrigin#PHOTO} receipt in
 * PENDING_CONFIRMATION, reviewed/confirmed through the normal flow, but it is
 * unverifiable (no SEFAZ document) and therefore NEVER feeds the collaborative
 * price index — personal history only.
 *
 * <p>Guard rails: feature flag ({@code economizaai.llm.photo-extraction.enabled}),
 * PRO-gate readiness ({@link Feature#PHOTO_EXTRACTION}, dormant while
 * enforcement is off), a per-user daily vision cap through the paid-API guard,
 * and a prompt that refuses non-receipt images so the endpoint can't be used
 * as a generic OCR service.
 */
@Slf4j
@Service
public class PhotoReceiptExtractionService {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OpenAiClient openAiClient;
    private final PhotoUploadValidator photoUploadValidator;
    private final PaidApiGuardService paidApiGuard;
    private final SubscriptionGateService subscriptionGate;
    private final ReceiptRepository receiptRepository;
    private final ReceiptService receiptService;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int maxItems;

    public PhotoReceiptExtractionService(OpenAiClient openAiClient,
                                         PhotoUploadValidator photoUploadValidator,
                                         PaidApiGuardService paidApiGuard,
                                         SubscriptionGateService subscriptionGate,
                                         ReceiptRepository receiptRepository,
                                         ReceiptService receiptService,
                                         TransactionTemplate transactionTemplate,
                                         @Value("${economizaai.llm.photo-extraction.enabled:true}") boolean enabled,
                                         @Value("${economizaai.llm.photo-extraction.max-items:60}") int maxItems) {
        this.openAiClient = openAiClient;
        this.photoUploadValidator = photoUploadValidator;
        this.paidApiGuard = paidApiGuard;
        this.subscriptionGate = subscriptionGate;
        this.receiptRepository = receiptRepository;
        this.receiptService = receiptService;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.maxItems = maxItems;
    }

    public UUID extract(User user, MultipartFile file) {
        if (!enabled || !openAiClient.isConfigured()) {
            throw new PhotoExtractionUnavailableException();
        }
        subscriptionGate.require(user, Feature.PHOTO_EXTRACTION);
        receiptService.enforceMonthlyReceiptCap(user); // photo receipts count toward the FREE cap
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertWithinDailyCap(user.getId(), PaidApiService.LLM_VISION);
        photoUploadValidator.readImage(file); // size/format validation; bytes go to the LLM raw

        JsonNode extraction;
        try {
            // HTTP untransacted — the vision call can take several seconds.
            extraction = openAiClient.completeJsonWithImage(systemPrompt(), "Extraia os itens desta nota.",
                    fileBytes(file), mediaType(file), 8000);
            paidApiGuard.recordSuccess(user.getId(), PaidApiService.LLM_VISION, null, "openai");
        } catch (LlmCallFailedException ex) {
            paidApiGuard.recordFailure(user.getId(), PaidApiService.LLM_VISION, null, "openai");
            log.warn("photo_extract.call_failed user={} reason={}", user.getId(), ex.getMessage());
            throw new PhotoExtractionUnavailableException();
        }
        if (!extraction.path("is_receipt").asBoolean(false)) {
            log.info("photo_extract.rejected reason=not_a_receipt user={}", user.getId());
            throw new InvalidReceiptPhotoException("receipt.photo.not-receipt");
        }
        var receiptId = transactionTemplate.execute(txStatus -> persistPhotoReceipt(user, extraction));
        log.info("photo_extract.ok user={} receipt={} items={}",
                user.getId(), receiptId, extraction.path("items").size());
        return receiptId;
    }

    private String systemPrompt() {
        return """
                Voce extrai itens de FOTOS de cupons fiscais brasileiros (DANFE NFC-e impresso). \
                Se a imagem NAO for um cupom fiscal/nota de compra, responda {"is_receipt": false}. \
                Nunca invente itens ou valores; omita o que estiver ilegivel. Valores com ponto decimal.
                Responda APENAS o JSON: {"is_receipt": true, "market_name": "...|null", \
                "issued_at": "yyyy-MM-dd HH:mm|null", "total_amount": <numero|null>, \
                "items": [{"description": "...", "quantity": <numero>, "unit": "UN|KG|...", \
                "unit_price": <numero|null>, "total_price": <numero|null>}]}""";
    }

    private UUID persistPhotoReceipt(User user, JsonNode extraction) {
        var receipt = Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .originHousehold(user.getHousehold())
                .chaveAcesso(syntheticChave())
                .qrPayload("PHOTO")
                .origin(ReceiptOrigin.PHOTO)
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .marketName(textOrNull(extraction, "market_name"))
                .issuedAt(parseIssuedAt(extraction))
                .totalAmount(decimalOrNull(extraction, "total_amount"))
                .build();
        var lineNumber = 1;
        for (var item : extraction.path("items")) {
            if (lineNumber > maxItems) break;
            var description = textOrNull(item, "description");
            if (description == null) continue;
            receipt.addItem(ReceiptItem.builder()
                    .lineNumber(lineNumber++)
                    .rawDescription(description)
                    .quantity(decimalOrDefault(item, "quantity", BigDecimal.ONE))
                    .unit(unitOrDefault(item))
                    .unitPrice(scaled(decimalOrNull(item, "unit_price")))
                    .totalPrice(scaled(decimalOrNull(item, "total_price")))
                    .nfcePromoFlag(false)
                    .build());
        }
        if (receipt.getItems().isEmpty()) {
            throw new InvalidReceiptPhotoException("receipt.photo.no-items");
        }
        return receiptRepository.save(receipt).getId();
    }

    /** 44 chars like a real chave, PH-prefixed so it can never collide with a SEFAZ one. */
    private static String syntheticChave() {
        return "PH" + UUID.randomUUID().toString().replace("-", "") + "0000000000";
    }

    private static byte[] fileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new InvalidReceiptPhotoException("receipt.photo.empty");
        }
    }

    private static String mediaType(MultipartFile file) {
        var contentType = file.getContentType();
        return contentType != null && contentType.startsWith("image/") ? contentType : "image/jpeg";
    }

    private static LocalDateTime parseIssuedAt(JsonNode extraction) {
        var raw = textOrNull(extraction, "issued_at");
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw, ISSUED_AT);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String unitOrDefault(JsonNode item) {
        var unit = textOrNull(item, "unit");
        return unit == null ? "UN" : unit.toUpperCase();
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? BigDecimal.valueOf(node.path(field).asDouble()) : null;
    }

    private static BigDecimal decimalOrDefault(JsonNode node, String field, BigDecimal fallback) {
        var value = decimalOrNull(node, field);
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        var text = value.asText().trim();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }
}
