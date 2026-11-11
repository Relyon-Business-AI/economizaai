package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptRawHtml;
import com.relyon.economizaai.repository.ReceiptRawHtmlRepository;
import com.relyon.economizaai.service.sefaz.CpfMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Single door to the stored portal document of a receipt (see {@link ReceiptRawHtml}).
 * Every write goes through {@link CpfMasker#strip}, so only CPF-free content is ever persisted.
 * Methods join the caller's transaction (the short block that persists the receipt outcome).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptRawHtmlService {

    private final ReceiptRawHtmlRepository receiptRawHtmlRepository;

    /**
     * Makes {@code html} the stored document of {@code receipt}: creates the row, overwrites the
     * existing one, or — when {@code html} is null — removes it (the receipt has no document).
     */
    @Transactional
    public void replace(Receipt receipt, String html) {
        if (html == null) {
            delete(receipt.getId());
            return;
        }
        var sanitized = CpfMasker.strip(html);
        receiptRawHtmlRepository.findById(receipt.getId()).ifPresentOrElse(
                stored -> stored.setRawHtml(sanitized),
                () -> receiptRawHtmlRepository.save(
                        ReceiptRawHtml.builder().receipt(receipt).rawHtml(sanitized).build()));
        log.debug("receipt.raw_html.stored chars={}", sanitized.length());
    }

    @Transactional(readOnly = true)
    public Optional<String> find(UUID receiptId) {
        return receiptRawHtmlRepository.findById(receiptId).map(ReceiptRawHtml::getRawHtml);
    }

    @Transactional
    public void delete(UUID receiptId) {
        receiptRawHtmlRepository.findById(receiptId).ifPresent(receiptRawHtmlRepository::delete);
    }

    /** Gives {@code destination} the same stored document as the source receipt, when it has one. */
    @Transactional
    public void copy(UUID sourceReceiptId, Receipt destination) {
        find(sourceReceiptId).ifPresent(html -> replace(destination, html));
    }
}
