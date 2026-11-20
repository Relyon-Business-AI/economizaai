package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ReceiptBatchTooLargeException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * How many receipts one bulk request may carry (import by chave, retry, confirm and delete in
 * batch). Changed in the admin settings and published to the apps as {@code bulkMaxReceipts}.
 */
@Component
public class ReceiptBatchLimit {

    /** Technical ceiling of a bulk payload; the admin setting cannot go above it. */
    public static final int HARD_CEILING = 5000;

    @Getter
    @Setter
    @Value("${economizaai.receipts.bulk-max-receipts:500}")
    private volatile int maxReceipts = 500;

    public void requireWithinLimit(Collection<?> receipts) {
        var allowed = maxReceipts;
        if (receipts.size() > allowed) {
            throw new ReceiptBatchTooLargeException(allowed);
        }
    }
}
