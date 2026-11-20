package com.relyon.economizaai.security.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many times per hour one user may send receipts, import them in bulk, preview an import
 * file and export the history. Changed in the admin settings; {@link RateLimitFilter} reads it
 * on every request.
 */
@Getter
@Setter
@Component
public class ReceiptQuotas {

    @Value("${economizaai.rate-limit.receipt-submits-per-hour:30}")
    private volatile int receiptSubmitsPerHour = 30;

    @Value("${economizaai.rate-limit.receipt-imports-per-hour:5}")
    private volatile int receiptImportsPerHour = 5;

    @Value("${economizaai.rate-limit.import-previews-per-hour:30}")
    private volatile int importPreviewsPerHour = 30;

    @Value("${economizaai.rate-limit.history-exports-per-hour:10}")
    private volatile int historyExportsPerHour = 10;
}
