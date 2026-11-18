package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.ReceiptMarketComparisonResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.priceindex.ReceiptMarketComparisonService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
@RequiredArgsConstructor
@Tag(name = "Receipt market comparison", description = "What one nota would have cost at nearby markets")
public class ReceiptMarketComparisonController {

    private final ReceiptMarketComparisonService receiptMarketComparisonService;

    /**
     * "Esta compra em outros mercados": what the items of this nota would have cost at up to
     * three nearby markets, comparing only the items each market has a price for.
     */
    @GetMapping("/{id}/market-comparison")
    public ResponseEntity<ReceiptMarketComparisonResponse> marketComparison(@AuthenticationPrincipal User user,
                                                                            @PathVariable UUID id) {
        return ResponseEntity.ok(receiptMarketComparisonService.compare(user, id));
    }
}
