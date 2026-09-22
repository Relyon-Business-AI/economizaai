package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateReceiptItemRequest(
        @Schema(description = "Original NFC-e text. **Read-only / ignored by the server** — kept in the request " +
                "shape for backwards compatibility, but the field is immutable after parsing. To rename an item " +
                "for display, set friendlyDescription instead.",
                example = "ARROZ TIO J TP1 5KG", deprecated = true)
        @Size(max = 500) String rawDescription,
        @Schema(description = "EAN barcode (digits only). Optional — leave null if the receipt didn't carry one.",
                example = "7891234567890")
        @Size(max = 14) String ean,
        @Schema(description = "Quantity purchased.", example = "3")
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Schema(description = "Sales unit (UN, KG, L, …).", example = "UN")
        @Size(max = 10) String unit,
        @Schema(description = "Price per unit. Optional if you only edit the total.")
        @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @Schema(description = "Total paid for this line. Should equal quantity × unitPrice for the math to check out.")
        @NotNull @DecimalMin(value = "0.0") BigDecimal totalPrice,
        @Schema(description = "Optional. true = exclude this item from spend / consumption / price-index. " +
                "Useful for items that belong to someone outside the household. Omit to leave the flag unchanged.")
        Boolean excluded,
        @Schema(description = "Optional. User-friendly display name shown to the household. rawDescription " +
                "stays untouched (it's the audit trail from SEFAZ). When set on an item already linked to a " +
                "Product, also remembered household-wide so future receipts of the same product inherit it. " +
                "Send empty string to clear.",
                example = "Cerveja Stella 330ml")
        @Size(max = 500) String friendlyDescription,
        @Schema(description = "Optional. Unit price actually paid when this line was on promotion. Leave null " +
                "if the line wasn't discounted; when null it is derived from paidTotalPrice ÷ quantity.")
        @DecimalMin(value = "0.0") BigDecimal paidUnitPrice,
        @Schema(description = "Optional. Total actually PAID for this line when on promotion — must not exceed " +
                "totalPrice. The original totalPrice stays as-printed (shelf price); this records what was " +
                "really paid so the app can show the discount. Send null to clear a previously-set manual discount.")
        @DecimalMin(value = "0.0") BigDecimal paidTotalPrice
) {}
