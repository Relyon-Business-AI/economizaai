package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.AddReceiptItemRequest;
import com.relyon.economizai.dto.request.ConfirmReceiptRequest;
import com.relyon.economizai.dto.request.PrefetchedReceiptRequest;
import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateItemCategoryRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ChaveExtractionResponse;
import com.relyon.economizai.exception.InvalidExportFormatException;
import com.relyon.economizai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.service.ReceiptExportService;
import com.relyon.economizai.service.ReceiptService;
import com.relyon.economizai.service.llm.PhotoReceiptExtractionService;
import com.relyon.economizai.service.report.ReportEmailService;
import com.relyon.economizai.service.scan.ChaveAcessoOcrService;
import com.relyon.economizai.service.scan.QrCodePhotoDecoder;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/receipts")
@RequiredArgsConstructor
@Tag(name = "Receipts", description = "NFC-e ingestion, review and confirmation")
public class ReceiptController {

    private final ReceiptService receiptService;
    private final ReceiptExportService receiptExportService;
    private final ReportEmailService reportEmailService;
    private final PhotoReceiptExtractionService photoReceiptExtractionService;
    private final QrCodePhotoDecoder qrCodePhotoDecoder;
    private final ChaveAcessoOcrService chaveAcessoOcrService;

    @PostMapping
    public ResponseEntity<ReceiptResponse> submit(@AuthenticationPrincipal User user,
                                                  @RequestHeader(value = "X-Device-Fetch", required = false) String deviceFetch,
                                                  @Valid @RequestBody SubmitReceiptRequest request) {
        // Client telemetry: the app reports whether it tried the on-device fetch (and why it
        // fell back to the server flow) so blocked-state failures are diagnosable end-to-end.
        if (deviceFetch != null) {
            log.info("client.device_fetch endpoint=receipts outcome={}", deviceFetch);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.submit(user, request));
    }

    /**
     * Submit a receipt whose SEFAZ page the APP already fetched on the device.
     * For portals that serve residential/mobile IPs but block our datacenter
     * server (Pernambuco): the phone reads its own nota and posts the raw content,
     * which we parse with the same pipeline as a server-side fetch — no scraping
     * from our IP, no paid fallback.
     */
    @PostMapping("/prefetched")
    public ResponseEntity<ReceiptResponse> submitPrefetched(@AuthenticationPrincipal User user,
                                                            @RequestHeader(value = "X-Device-Fetch", required = false) String deviceFetch,
                                                            @Valid @RequestBody PrefetchedReceiptRequest request) {
        if (deviceFetch != null) {
            log.info("client.device_fetch endpoint=prefetched outcome={}", deviceFetch);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.submitPrefetched(user, request));
    }

    /**
     * Submit from a PHOTO of the QR code (web upload / gallery picture) —
     * decodes server-side and enters the exact same flow as a live scan.
     */
    @PostMapping("/photo")
    public ResponseEntity<ReceiptResponse> submitPhoto(@AuthenticationPrincipal User user,
                                                       @RequestParam("file") MultipartFile file) {
        var qrPayload = qrCodePhotoDecoder.decode(file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(receiptService.submit(user, new SubmitReceiptRequest(qrPayload)));
    }

    /**
     * Extract items from a PHOTO of the printed receipt via vision LLM — the
     * fallback when SEFAZ can't serve the nota (contingency, unreadable QR).
     * Creates a PENDING_CONFIRMATION receipt with origin=PHOTO (personal
     * history only; never contributes to the collaborative index).
     */
    @PostMapping("/items-photo")
    public ResponseEntity<ReceiptResponse> extractItemsFromPhoto(@AuthenticationPrincipal User user,
                                                                 @RequestParam("file") MultipartFile file) {
        var receiptId = photoReceiptExtractionService.extract(user, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.get(user, receiptId));
    }

    /**
     * OCR the printed 44-digit chave off a photo (fallback for an unreadable
     * QR). Returns the chave for the user to confirm — the FE then submits it
     * through the normal endpoint, so misreads never auto-ingest.
     */
    @PostMapping("/chave/photo")
    public ResponseEntity<ChaveExtractionResponse> extractChaveFromPhoto(@AuthenticationPrincipal User user,
                                                                         @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(chaveAcessoOcrService.extractChave(file));
    }

    @GetMapping
    public ResponseEntity<Page<ReceiptSummaryResponse>> list(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String marketCnpj,
            @RequestParam(required = false) List<ProductCategory> category,
            @RequestParam(required = false) ReceiptStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(receiptService.list(user, from, to, marketCnpj, category, status, q, pageable));
    }

    /**
     * The household's confirmed purchase history as CSV (default, flat
     * Brazilian-Excel-friendly), XLSX (multi-sheet report with native charts)
     * or PDF (styled report with charts). {@code delivery=download} (default)
     * streams the file; {@code delivery=email} sends it as an attachment to
     * the account's own e-mail and returns 202. PRO-gated (dormant while
     * subscription enforcement is off); FREE history window applies.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "download") String delivery) {
        var exportFormat = parseFormat(format);
        var exportDelivery = parseDelivery(delivery);
        var file = receiptExportService.exportPurchaseHistory(user, from, to, exportFormat);
        var filename = "economizai-historico-" + LocalDate.now() + "." + file.fileExtension();
        if (exportDelivery == Delivery.EMAIL) {
            reportEmailService.sendToOwnEmail(user, file, filename);
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .body(file.content());
    }

    private enum Delivery { DOWNLOAD, EMAIL }

    /** Case-insensitive — MVC's enum binding is case-sensitive and would 400 on "csv"/"xlsx". */
    private static ReceiptExportService.ExportFormat parseFormat(String format) {
        try {
            return ReceiptExportService.ExportFormat.valueOf(format.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidExportFormatException(format);
        }
    }

    private static Delivery parseDelivery(String delivery) {
        try {
            return Delivery.valueOf(delivery.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidExportFormatException(delivery);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReceiptResponse> get(@AuthenticationPrincipal User user,
                                               @PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.get(user, id));
    }

    @PatchMapping("/{id}/items/{itemId}")
    public ResponseEntity<ReceiptResponse> updateItem(@AuthenticationPrincipal User user,
                                                      @PathVariable UUID id,
                                                      @PathVariable UUID itemId,
                                                      @Valid @RequestBody UpdateReceiptItemRequest request) {
        return ResponseEntity.ok(receiptService.updateItem(user, id, itemId, request));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<ReceiptResponse> addItem(@AuthenticationPrincipal User user,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody AddReceiptItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.addItem(user, id, request));
    }

    /**
     * User category correction — household-scoped "evidence, not truth": shows
     * for this household only; the global product is untouched.
     */
    @PutMapping("/{id}/items/{itemId}/category")
    public ResponseEntity<ReceiptResponse> updateItemCategory(@AuthenticationPrincipal User user,
                                                              @PathVariable UUID id,
                                                              @PathVariable UUID itemId,
                                                              @Valid @RequestBody UpdateItemCategoryRequest request) {
        return ResponseEntity.ok(receiptService.updateItemCategory(user, id, itemId, request.category()));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ConfirmReceiptResponse> confirm(@AuthenticationPrincipal User user,
                                                          @PathVariable UUID id,
                                                          @RequestBody(required = false) ConfirmReceiptRequest request) {
        return ResponseEntity.ok(receiptService.confirm(user, id, request));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ReceiptResponse> reject(@AuthenticationPrincipal User user,
                                                  @PathVariable UUID id) {
        return ResponseEntity.ok(receiptService.reject(user, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        receiptService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
