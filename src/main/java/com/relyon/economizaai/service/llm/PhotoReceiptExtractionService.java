package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.exception.InvalidReceiptPhotoException;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.PhotoExtractionUnavailableException;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ReceiptEntryMethod;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.ReceiptService;
import com.relyon.economizaai.service.scan.PhotoUploadValidator;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    private final AiGateway aiGateway;
    // fasterxml local: o bean Spring do SB4 é o Jackson 3 (tools.jackson), tipo incompatível
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PhotoUploadValidator photoUploadValidator;
    private final PaidApiGuardService paidApiGuard;
    private final SubscriptionGateService subscriptionGate;
    private final ReceiptRepository receiptRepository;
    private final ReceiptService receiptService;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int maxItems;
    private final int maxPhotos;

    // Self-ref pra o @Async valer (chamada de método no MESMO bean roda inline, sem proxy).
    @Lazy
    @Autowired
    private PhotoReceiptExtractionService self = this;

    public PhotoReceiptExtractionService(AiGateway aiGateway,
                                         PhotoUploadValidator photoUploadValidator,
                                         PaidApiGuardService paidApiGuard,
                                         SubscriptionGateService subscriptionGate,
                                         ReceiptRepository receiptRepository,
                                         ReceiptService receiptService,
                                         TransactionTemplate transactionTemplate,
                                         @Value("${economizaai.llm.photo-extraction.enabled:true}") boolean enabled,
                                         @Value("${economizaai.llm.photo-extraction.max-items:60}") int maxItems,
                                         @Value("${economizaai.llm.photo-extraction.max-photos:5}") int maxPhotos) {
        this.aiGateway = aiGateway;
        this.photoUploadValidator = photoUploadValidator;
        this.paidApiGuard = paidApiGuard;
        this.subscriptionGate = subscriptionGate;
        this.receiptRepository = receiptRepository;
        this.receiptService = receiptService;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.maxItems = maxItems;
        this.maxPhotos = maxPhotos;
    }

    public UUID extract(User user, MultipartFile file) {
        return extract(user, List.of(file));
    }

    /** Bytes + media type de uma foto, lidos ANTES de a request morrer (pro trabalho async). */
    private record PhotoData(byte[] bytes, String mediaType) {}

    /**
     * ASSÍNCRONO: valida rápido (fail-fast 4xx), cria a nota em PROCESSING e retorna JÁ —
     * a extração de visão (lenta, paga, várias fotos) roda em background e transiciona a nota
     * pra PENDING_CONFIRMATION (ou FAILED_PARSE). O app pode sair da tela: a nota aparece em
     * Minhas Notas quando terminar (igual ao scan normal; o {@code ProcessingReceiptSweeper}
     * força-falha se ficar presa).
     */
    public UUID extract(User user, List<MultipartFile> files) {
        if (!enabled || !aiGateway.isEnabled()) {
            throw new PhotoExtractionUnavailableException();
        }
        if (files == null || files.isEmpty()) {
            throw new InvalidReceiptPhotoException("receipt.photo.empty");
        }
        if (files.size() > maxPhotos) {
            // Fail-fast: truncar silenciosamente perderia o fim da nota sem o usuário saber.
            throw new InvalidReceiptPhotoException("receipt.photo.too-many", String.valueOf(maxPhotos));
        }
        subscriptionGate.require(user, Feature.PHOTO_EXTRACTION);
        receiptService.enforceMonthlyReceiptCap(user); // photo receipts count toward the FREE cap
        // Validação determinística de TODAS as fotos + cota ANTES de criar a nota — um usuário
        // sem cota recebe 429/503 na hora, não uma nota que falha depois.
        files.forEach(photoUploadValidator::readImage);
        paidApiGuard.assertUnderGlobalBudget();
        paidApiGuard.assertWithinDailyCap(user.getId(), PaidApiService.LLM_VISION);

        // Lê os bytes AGORA (o MultipartFile é da request, que termina ao retornarmos).
        var photoData = files.stream().map(file -> new PhotoData(fileBytes(file), mediaType(file))).toList();
        var receiptId = transactionTemplate.execute(txStatus -> createProcessingReceipt(user));
        try {
            self.processPhotosAsync(receiptId, user, photoData);
        } catch (TaskRejectedException poolFull) {
            // Pool cheio: a nota já está committed como PROCESSING; falha-a agora pra não pendurar.
            markFailed(receiptId, "receipt.photo.extraction-failed");
        }
        log.info("photo_extract.accepted user={} receipt={} photos={}", user.getId(), receiptId, photoData.size());
        return receiptId;
    }

    private UUID createProcessingReceipt(User user) {
        var receipt = Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .originHousehold(user.getHousehold())
                .chaveAcesso(syntheticChave())
                .qrPayload("PHOTO")
                .origin(ReceiptOrigin.PHOTO)
                .entryMethod(ReceiptEntryMethod.PHOTO_RECEIPT)
                .status(ReceiptStatus.PROCESSING)
                .build();
        return receiptRepository.save(receipt).getId();
    }

    /**
     * Trabalho pesado em background: cada foto é uma chamada de visão (paga, cap por-usuário).
     * Uma foto cuja IA falha é PULADA; cap no meio do lote → nota PARCIAL com o que já foi pago.
     * Sem sucesso nenhum / nenhuma é cupom → FAILED_PARSE. Roda num bean-boundary (via {@code self})
     * pra o {@code @Async} valer; exceções NÃO propagam (nada útil sai de thread async).
     */
    @Async(AsyncConfig.AI_SWEEP_EXECUTOR)
    public void processPhotosAsync(UUID receiptId, User user, List<PhotoData> photos) {
        var extractions = new ArrayList<JsonNode>();
        var anyVisionCallSucceeded = false;
        for (var photo : photos) {
            try {
                paidApiGuard.assertUnderGlobalBudget();
                paidApiGuard.assertWithinDailyCap(user.getId(), PaidApiService.LLM_VISION);
            } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException capEx) {
                log.warn("photo_extract.cap_hit_mid_batch user={} extracted={}", user.getId(), extractions.size());
                break;
            }
            try {
                var raw = aiGateway.completeWithImage(AiActivity.PHOTO_EXTRACT, aiGateway.extractorModel(),
                        systemPrompt(), "Extraia os itens desta nota.", photo.bytes(), photo.mediaType(), 8000, user.getId());
                var extraction = parseJson(raw);
                paidApiGuard.recordSuccess(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
                anyVisionCallSucceeded = true;
                if (extraction.path("is_receipt").asBoolean(false)) {
                    extractions.add(extraction);
                }
            } catch (AiGateway.AiUnavailableException | PhotoExtractionUnavailableException ex) {
                paidApiGuard.recordFailure(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
                log.warn("photo_extract.photo_failed user={} reason={} — pulando essa foto", user.getId(), ex.getMessage());
            }
        }
        if (!anyVisionCallSucceeded) {
            markFailed(receiptId, "receipt.photo.extraction-failed");
            return;
        }
        if (extractions.isEmpty()) {
            log.info("photo_extract.rejected reason=not_a_receipt user={} photos={}", user.getId(), photos.size());
            markFailed(receiptId, "receipt.photo.not-receipt");
            return;
        }
        transactionTemplate.execute(txStatus -> finalizePhotoReceipt(receiptId, extractions));
        log.info("photo_extract.ok user={} receipt={} photos={}", user.getId(), receiptId, photos.size());
    }

    private Receipt markFailed(UUID receiptId, String reasonKey) {
        return transactionTemplate.execute(txStatus -> {
            var receipt = receiptRepository.findById(receiptId).orElse(null);
            if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) return receipt; // já variou (sweeper etc.)
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receipt.setParseErrorReason(reasonKey);
            return receiptRepository.save(receipt);
        });
    }

    /** O modelo responde JSON puro; tolera cercas de markdown que às vezes escapam. */
    private JsonNode parseJson(String raw) {
        var cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "");
        }
        try {
            return OBJECT_MAPPER.readTree(cleaned);
        } catch (Exception ex) {
            log.warn("photo_extract.bad_json len={}", raw.length());
            throw new PhotoExtractionUnavailableException();
        }
    }

    private String systemPrompt() {
        return """
                Voce extrai itens de FOTOS de cupons fiscais brasileiros (DANFE NFC-e impresso, papel termico). \
                Se a imagem NAO for um cupom fiscal/nota de compra, responda {"is_receipt": false}.

                ESTRUTURA do cupom: cabecalho (mercado, CNPJ no formato NN.NNN.NNN/NNNN-NN, data) e uma \
                tabela de itens. Cada item ocupa DUAS linhas: \
                "<seq> <codigo> <DESCRICAO>" e abaixo "<qtd> <UN> X <vl_unit>" com o <vl_total> a direita. \
                Ex.: "001 0000001068998 BATATA PALHA TUTTI EF 100G" / "3.000 UN X 7,48    22,44".

                REGRAS:
                - Descricao: transcreva EXATAMENTE como impresso (abreviacoes de mercado: QJO=queijo, \
                  MANT=manteiga, LR/CR=creme, MUSS=mussarela, etc.). NAO traduza nem complete.
                - Coerencia: quantity * unit_price deve bater com total_price (arredonde centavos). Se nao \
                  bater, releia os numeros — provavelmente um digito foi lido errado.
                - Papel termico desbota: se um item estiver ILEGIVEL, OMITA-O (nao invente). O usuario \
                  completa na revisao. Melhor faltar do que inventar.
                - Use o "QTD. TOTAL DE ITENS" e o "VALOR TOTAL" impressos como conferencia do seu resultado.
                - Valores com ponto decimal. Capture o CNPJ do cabecalho (so os 14 digitos) se visivel.

                Responda APENAS o JSON: {"is_receipt": true, "market_name": "...|null", "cnpj": "<14 digitos>|null", \
                "issued_at": "yyyy-MM-dd HH:mm|null", "total_amount": <numero|null>, \
                "items": [{"description": "...", "quantity": <numero>, "unit": "UN|KG|...", \
                "unit_price": <numero|null>, "total_price": <numero|null>}]}""";
    }

    /** Preenche a nota PROCESSING existente com o resultado da visão e a transiciona. */
    private Receipt finalizePhotoReceipt(UUID receiptId, List<JsonNode> extractions) {
        var receipt = receiptRepository.findById(receiptId).orElse(null);
        if (receipt == null || receipt.getStatus() != ReceiptStatus.PROCESSING) return receipt; // swept/mudou
        // Cabeçalho (mercado/CNPJ/data/total) vem da 1ª foto que o tiver.
        receipt.setMarketName(firstText(extractions, "market_name"));
        receipt.setCnpjEmitente(firstCnpj(extractions));
        receipt.setIssuedAt(firstIssuedAt(extractions));
        receipt.setTotalAmount(firstDecimal(extractions, "total_amount"));
        var items = stitchItems(extractions);
        var lineNumber = 1;
        for (var item : items) {
            if (lineNumber > maxItems) break;
            receipt.addItem(ReceiptItem.builder()
                    .lineNumber(lineNumber++)
                    .rawDescription(item.description())
                    .quantity(item.quantity())
                    .unit(item.unit())
                    .unitPrice(item.unitPrice())
                    .totalPrice(item.totalPrice())
                    .nfcePromoFlag(false)
                    .build());
        }
        if (receipt.getItems().isEmpty()) {
            receipt.setStatus(ReceiptStatus.FAILED_PARSE);
            receipt.setParseErrorReason("receipt.photo.no-items");
        } else {
            receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
        }
        return receiptRepository.save(receipt);
    }

    /** Item extraído de uma foto, já normalizado, com uma chave pra detectar sobreposição. */
    record PhotoItem(String description, BigDecimal quantity, String unit,
                     BigDecimal unitPrice, BigDecimal totalPrice, String overlapKey) {}

    /**
     * Junta as fotos COSTURANDO pela sobreposição, não concatenando. Fotos consecutivas
     * de uma nota longa se sobrepõem (o fim de uma foto = o começo da próxima); concatenar
     * duplicava esses itens (35 viravam ~60). Aqui, ao emendar a foto seguinte, descartamos
     * o maior PREFIXO dela que repete o SUFIXO do que já temos — um run contíguo na junção.
     *
     * <p>Respeita compra legítima de itens repetidos: um produto comprado várias vezes só
     * é fundido se formar esse run contíguo exatamente na emenda entre duas fotos; repetições
     * espalhadas pela nota (caixa passou fora de ordem) ficam intactas.
     */
    List<PhotoItem> stitchItems(List<JsonNode> extractions) {
        var merged = new ArrayList<PhotoItem>();
        for (var extraction : extractions) {
            var photoItems = new ArrayList<PhotoItem>();
            for (var node : extraction.path("items")) {
                var description = textOrNull(node, "description");
                if (description == null) continue;
                photoItems.add(toPhotoItem(node, description));
            }
            if (merged.isEmpty()) {
                merged.addAll(photoItems);
                continue;
            }
            var overlap = overlapLength(merged, photoItems);
            merged.addAll(photoItems.subList(overlap, photoItems.size()));
        }
        return merged;
    }

    /** Maior k tal que os últimos k de {@code merged} == os primeiros k de {@code next} (pela chave). */
    private int overlapLength(List<PhotoItem> merged, List<PhotoItem> next) {
        var max = Math.min(merged.size(), next.size());
        for (var k = max; k >= 1; k--) {
            var matches = true;
            for (var i = 0; i < k; i++) {
                if (!merged.get(merged.size() - k + i).overlapKey().equals(next.get(i).overlapKey())) {
                    matches = false;
                    break;
                }
            }
            if (matches) return k;
        }
        return 0;
    }

    private PhotoItem toPhotoItem(JsonNode node, String description) {
        var quantity = decimalOrDefault(node, "quantity", BigDecimal.ONE);
        var unit = unitOrDefault(node);
        var unitPrice = scaled(decimalOrNull(node, "unit_price"));
        var totalPrice = scaled(decimalOrNull(node, "total_price"));
        var key = description.toUpperCase().replaceAll("\\s+", " ").trim()
                + "|" + quantity.stripTrailingZeros().toPlainString()
                + "|" + (unitPrice == null ? "" : unitPrice.toPlainString())
                + "|" + (totalPrice == null ? "" : totalPrice.toPlainString());
        return new PhotoItem(description, quantity, unit, unitPrice, totalPrice, key);
    }

    /** Primeiro CNPJ válido (14 dígitos) extraído das fotos, ou null. */
    private static String firstCnpj(List<JsonNode> extractions) {
        return extractions.stream()
                .map(extraction -> textOrNull(extraction, "cnpj"))
                .filter(Objects::nonNull)
                .map(raw -> raw.replaceAll("\\D", ""))
                .filter(digits -> digits.length() == 14)
                .findFirst()
                .orElse(null);
    }

    private static String firstText(List<JsonNode> extractions, String field) {
        return extractions.stream().map(extraction -> textOrNull(extraction, field)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static BigDecimal firstDecimal(List<JsonNode> extractions, String field) {
        return extractions.stream().map(extraction -> decimalOrNull(extraction, field)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static LocalDateTime firstIssuedAt(List<JsonNode> extractions) {
        return extractions.stream().map(PhotoReceiptExtractionService::parseIssuedAt).filter(Objects::nonNull).findFirst().orElse(null);
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
