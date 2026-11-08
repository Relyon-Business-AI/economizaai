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
import com.relyon.economizaai.service.photoattempt.PhotoRetentionService;
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
import java.text.Normalizer;
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
    private final PhotoRetentionService photoRetentionService;
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
                                         PhotoRetentionService photoRetentionService,
                                         @Value("${economizaai.llm.photo-extraction.enabled:true}") boolean enabled,
                                         @Value("${economizaai.llm.photo-extraction.max-items:60}") int maxItems,
                                         @Value("${economizaai.llm.photo-extraction.max-photos:6}") int maxPhotos) {
        this.aiGateway = aiGateway;
        this.photoUploadValidator = photoUploadValidator;
        this.paidApiGuard = paidApiGuard;
        this.subscriptionGate = subscriptionGate;
        this.receiptRepository = receiptRepository;
        this.receiptService = receiptService;
        this.transactionTemplate = transactionTemplate;
        this.photoRetentionService = photoRetentionService;
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
        // Best-effort: guarda as fotos da tentativa (p/ replay enquanto afinamos o modelo).
        // Temporário e desligável (toggle); nunca quebra a ingestão.
        photoRetentionService.saveAttempt(receiptId,
                photoData.stream().map(PhotoData::bytes).toList(),
                photoData.stream().map(PhotoData::mediaType).toList());
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
            String raw;
            try {
                raw = aiGateway.completeWithImage(AiActivity.PHOTO_EXTRACT, aiGateway.photoExtractModel(),
                        systemPrompt(), "Extraia os itens desta nota.", photo.bytes(), photo.mediaType(), 8000, user.getId());
            } catch (AiGateway.AiUnavailableException ex) {
                // A chamada de visão em si falhou (provedor fora/cota) — ISSO sim é falha do provedor.
                paidApiGuard.recordFailure(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
                log.warn("photo_extract.photo_failed user={} reason={} — provedor indisponível, pulando", user.getId(), ex.getMessage());
                continue;
            }
            // Chamada teve SUCESSO (respondeu, pagamos): marca sucesso ANTES do parse. Um JSON ruim
            // é problema de OUTPUT do modelo, não indisponibilidade — não pode tripar o circuit breaker.
            paidApiGuard.recordSuccess(user.getId(), PaidApiService.LLM_VISION, null, "anthropic");
            anyVisionCallSucceeded = true;
            var extraction = parseJson(raw);
            if (extraction != null && extraction.path("is_receipt").asBoolean(false)) {
                extractions.add(extraction);
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
        try {
            transactionTemplate.execute(txStatus -> finalizePhotoReceipt(receiptId, extractions));
        } catch (RuntimeException ex) {
            // Nada pode escapar daqui sem transicionar a nota — senão ela fica presa em PROCESSING
            // até o sweeper (o usuário espera ~10min por um erro). Força FAILED_PARSE e segue.
            log.error("photo_extract.finalize_failed receipt={} — marcando FAILED_PARSE", receiptId, ex);
            markFailed(receiptId, "receipt.photo.extraction-failed");
            return;
        }
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

    /**
     * O modelo deveria responder JSON puro, mas às vezes embrulha: cerca de markdown, uma frase
     * antes ("Aqui estão os itens:"), ou texto depois. Tenta direto; se falhar, extrai do PRIMEIRO
     * '{' ao ÚLTIMO '}' e tenta de novo. Retorna {@code null} se nem assim for JSON — o chamador
     * PULA a foto (não é falha do provedor, então não conta pro circuit breaker).
     */
    JsonNode parseJson(String raw) {
        if (raw == null) return null;
        var cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        var direct = tryReadTree(cleaned);
        if (direct != null) return direct;
        var start = cleaned.indexOf('{');
        var end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            var extracted = tryReadTree(cleaned.substring(start, end + 1));
            if (extracted != null) return extracted;
        }
        log.warn("photo_extract.bad_json len={}", raw.length());
        return null;
    }

    private static JsonNode tryReadTree(String candidate) {
        try {
            var node = OBJECT_MAPPER.readTree(candidate);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
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

    /** Item extraído de uma foto, já normalizado, com a descrição normalizada p/ casar sobreposição. */
    record PhotoItem(String description, BigDecimal quantity, String unit,
                     BigDecimal unitPrice, BigDecimal totalPrice, String normDesc) {}

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

    /**
     * Comprimento da sobreposição na emenda: maior k tal que os ÚLTIMOS k de {@code merged} são a
     * MESMA SEQUÊNCIA que os PRIMEIROS k de {@code next}. O casamento é por SEMELHANÇA, não igualdade
     * exata — o OCR lê o mesmo item de formas ligeiramente diferentes em cada foto (acento, sufixo
     * "1UN", um preço que faltou). Exigimos um RUN contíguo: é o "contexto ao redor" — uma sequência
     * inteira coincidente é sobreposição; um item solto que casa mas tem vizinhos diferentes é compra
     * repetida legítima e NÃO é fundido (um run de 1 só funde com prova forte: descrição quase igual
     * E mesmo total).
     */
    private int overlapLength(List<PhotoItem> merged, List<PhotoItem> next) {
        var max = Math.min(merged.size(), next.size());
        for (var k = max; k >= 1; k--) {
            if (!seamMatches(merged, next, k)) continue;
            if (k >= 2) return k;
            if (strongSameLine(merged.get(merged.size() - 1), next.get(0))) return 1;
        }
        return 0;
    }

    /** Os últimos k de {@code merged} casam (por semelhança) com os primeiros k de {@code next}? */
    private boolean seamMatches(List<PhotoItem> merged, List<PhotoItem> next, int k) {
        for (var i = 0; i < k; i++) {
            if (!sameLine(merged.get(merged.size() - k + i), next.get(i))) return false;
        }
        return true;
    }

    /** Mesmo item lido em duas fotos, tolerante a ruído de OCR (descrição parecida, ou preço+qtd batendo). */
    private static boolean sameLine(PhotoItem left, PhotoItem right) {
        var similarity = descSimilarity(left.normDesc(), right.normDesc());
        if (similarity >= 0.82) return true;
        return similarity >= 0.5 && left.quantity().compareTo(right.quantity()) == 0
                && left.totalPrice() != null && right.totalPrice() != null
                && left.totalPrice().compareTo(right.totalPrice()) == 0;
    }

    /** Prova forte pra fundir um run de UM item só (sem vizinhança pra confirmar): quase idêntico + mesmo total. */
    private static boolean strongSameLine(PhotoItem left, PhotoItem right) {
        return descSimilarity(left.normDesc(), right.normDesc()) >= 0.9
                && left.totalPrice() != null && right.totalPrice() != null
                && left.totalPrice().compareTo(right.totalPrice()) == 0;
    }

    /** Semelhança [0,1] entre duas descrições normalizadas (1 − distância de edição ÷ maior tamanho). */
    private static double descSimilarity(String left, String right) {
        if (left.isEmpty() && right.isEmpty()) return 1.0;
        var longest = Math.max(left.length(), right.length());
        return longest == 0 ? 1.0 : 1.0 - (double) levenshtein(left, right) / longest;
    }

    private static int levenshtein(String left, String right) {
        var previous = new int[right.length() + 1];
        var current = new int[right.length() + 1];
        for (var j = 0; j <= right.length(); j++) previous[j] = j;
        for (var i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (var j = 1; j <= right.length(); j++) {
                var cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            var swap = previous; previous = current; current = swap;
        }
        return previous[right.length()];
    }

    private PhotoItem toPhotoItem(JsonNode node, String description) {
        var quantity = decimalOrDefault(node, "quantity", BigDecimal.ONE);
        var unit = unitOrDefault(node);
        var unitPrice = scaled(decimalOrNull(node, "unit_price"));
        var totalPrice = scaled(decimalOrNull(node, "total_price"));
        return new PhotoItem(description, quantity, unit, unitPrice, totalPrice, normalizeDesc(description));
    }

    /** Descrição sem acento, caixa-alta e espaços colapsados — base pra casar itens entre fotos. */
    private static String normalizeDesc(String description) {
        var noAccent = Normalizer.normalize(description, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccent.toUpperCase().replaceAll("\\s+", " ").trim();
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
