package com.relyon.economizaai.dto.response;

import java.util.UUID;

public record ProductDeletionResponse(UUID productId, long receiptItemsDetached) {
}
