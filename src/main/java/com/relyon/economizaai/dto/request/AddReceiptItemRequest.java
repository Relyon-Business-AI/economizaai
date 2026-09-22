package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Used when the SVRS parser missed an item the user actually paid for.
 * Adds a new line at the end of a PENDING_CONFIRMATION receipt.
 */
public record AddReceiptItemRequest(
        @Schema(description = "Item text to record. Becomes the rawDescription on the new line.",
                example = "ARROZ TIO J 5KG")
        @NotBlank @Size(max = 500) String rawDescription,
        @Schema(description = "EAN barcode (digits only). Optional.", example = "7891234567890")
        @Size(max = 14) String ean,
        @Schema(description = "Quantity purchased.", example = "1")
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
        @Schema(description = "Sales unit (UN, KG, L, …).", example = "UN")
        @Size(max = 10) String unit,
        @Schema(description = "Price per unit. Optional.")
        @DecimalMin(value = "0.0") BigDecimal unitPrice,
        @Schema(description = "Total paid for this line.")
        @NotNull @DecimalMin(value = "0.0") BigDecimal totalPrice,
        @Schema(description = "Optional friendly display name. Defaults to rawDescription on render if null.")
        @Size(max = 500) String friendlyDescription
) {}
